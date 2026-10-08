package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.file.TextDocumentCache;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 旧委派调用没有普通工具记录，全文仍从实际保存的消息读取，每次重新检查会话和来源权限。
 */
@Service
public class NativeToolContentService {
    private final RunMapper runs;
    private final ExecutionMessageMapper messages;
    private final ConversationQueryService conversations;
    private final ExecutionAccessService access;
    private final TextDocumentCache cache = new TextDocumentCache(16L * 1024 * 1024);

    public NativeToolContentService(RunMapper runs, ExecutionMessageMapper messages,
                                    ConversationQueryService conversations, ExecutionAccessService access) {
        this.runs = runs;
        this.messages = messages;
        this.conversations = conversations;
        this.access = access;
    }

    public TextFileDocument open(AuthContext actor, String runId, String step, String part, String view,
                                 String revision) {
        if (!Set.of("input", "result").contains(part) || !Set.of("readable", "raw").contains(view)) {
            throw unavailable();
        }
        var run = runs.find(actor.enterpriseId(), runId, false).filter(value -> actor.userId().equals(value.userId()))
            .orElseThrow(NativeToolContentService::unavailable);
        conversations.readable(actor, run.conversationId());
        access.actor(run);
        var block = messages.savedToolBlock(actor.enterpriseId(), run.conversationId(), run.outputMessageId(), step)
            .filter(value -> Set.of("agent_spawn", "agent_send").contains(value.tool().name()))
            .orElseThrow(NativeToolContentService::unavailable);
        String text = part.equals("input") ? block.tool().input() : block.tool().result();
        String content = text == null ? "" : text;
        String path = "tool-results/" + runId + "/" + step + "/" + part;
        String version = Utf8Text.revision(path, content);
        if (revision != null && !version.equals(revision)) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_CONTENT_CHANGED", "工具内容已经更新，请重新展开查看。");
        }
        return cache.get(actor.enterpriseId() + ":" + path + ":" + version,
            () -> new TextFileDocument(path, version, content));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "TOOL_CONTENT_UNAVAILABLE", "工具内容不存在或已不可读取。");
    }
}
