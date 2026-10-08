package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.response.FileTextSlice;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.file.TextDocumentCache;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 用户展开工具详情时读取完整脱敏内容，不创建智能体步骤。
 */
@Service
public class ToolPayloadContentService {
    private final ToolCallMapper calls;
    private final RunMapper runs;
    private final ToolResultDocumentService documents;
    private final ToolLogService logs;
    private final NativeToolContentService nativeContent;
    private final TextDocumentCache cache = new TextDocumentCache(64L * 1024 * 1024);

    public ToolPayloadContentService(ToolCallMapper calls, RunMapper runs, ToolResultDocumentService documents,
                                     ToolLogService logs, NativeToolContentService nativeContent) {
        this.calls = calls;
        this.runs = runs;
        this.documents = documents;
        this.logs = logs;
        this.nativeContent = nativeContent;
    }

    public FileTextSlice forStep(AuthContext actor, String run, String step, String part, String view, int offset,
                                 boolean backwards, int limit, String revision) {
        checkWindow(offset, limit);
        return stepDocument(actor, run, step, part, view, revision).window(offset, limit, backwards);
    }

    public FileTextSlice forLog(AuthContext actor, String id, String part, String view, int offset, boolean backwards,
                                int limit, String revision) {
        checkWindow(offset, limit);
        return logDocument(actor, id, part, view, revision).window(offset, limit, backwards);
    }

    public TextFileDocument stepDocument(AuthContext actor, String run, String step, String part, String view,
                                         String revision) {
        var call = calls.forStep(actor.enterpriseId(), run, step).orElse(null);
        if (call == null || part.equals("input") && Set.of("read_agent_spawn_result", "read_agent_send_result")
            .contains(call.toolName())) {
            return nativeContent.open(actor, run, step, part, view, revision);
        }
        return document(actor, call, part, view, revision, false);
    }

    public TextFileDocument logDocument(AuthContext actor, String id, String part, String view, String revision) {
        logs.details(actor, id);
        var call = calls.find(actor.enterpriseId(), id, false).orElseThrow(ToolPayloadContentService::unavailable);
        return document(actor, call, part, view, revision, true);
    }

    private void checkWindow(int offset, int limit) {
        if (offset < 0 || limit < 4 || limit > 256 * 1024) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_CONTENT_RANGE_INVALID", "读取范围不正确。");
        }
    }

    private TextFileDocument document(AuthContext actor, ToolCallRecord call, String part, String view, String revision,
                                      boolean logAuthorized) {
        if (!Set.of("input", "result").contains(part) || !Set.of("readable", "raw").contains(view)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_CONTENT_RANGE_INVALID", "内容类型或读取范围不正确。");
        }
        if (!logAuthorized) {
            var run = runs.find(actor.enterpriseId(), call.runId(), false)
                .orElseThrow(ToolPayloadContentService::unavailable);
            documents.require(actor, run.conversationId(), call);
        }
        if (part.equals("result")) {
            documents.requireStoredContent(actor, call, logAuthorized);
        }
        String path = "tool-results/" + call.id() + "/" + part + "-" + view;
        var stored = part.equals("input") ? call.requestRedacted() : call.resultRedacted();
        String key = call.enterpriseId() + ":" + path + ":" + Utf8Text.revision(path,
            stored == null ? "" : stored.toString());
        var document = cache.get(key, () -> {
            var value = part.equals(
                "input") ? call.requestRedacted() : logAuthorized ? documents.resultAfterLogAuthorization(actor,
                call) : documents.result(actor, call);
            String text = value == null ? "" : view.equals("readable") && part.equals(
                "result") && ToolResultContent.hasText(value) ? ToolResultContent.text(value) : value.toPrettyString();
            return new TextFileDocument(path, Utf8Text.revision(path, text), text);
        });
        if (revision != null && !document.revision().equals(revision)) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_CONTENT_CHANGED", "工具内容已经更新，请重新展开查看。");
        }
        return document;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "TOOL_CONTENT_UNAVAILABLE", "工具内容不存在或已不可读取。");
    }
}
