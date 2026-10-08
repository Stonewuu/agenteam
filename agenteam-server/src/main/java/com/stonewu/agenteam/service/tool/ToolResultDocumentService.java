package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.execution.ConversationQueryService;
import com.stonewu.agenteam.service.file.*;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * 保存结果的只读视图；文件路径不能扩大用户、对话或子任务的授权范围。
 */
@Service
public class ToolResultDocumentService {
    private final ToolCallMapper calls;
    private final RunMapper runs;
    private final ConversationQueryService conversations;
    private final SourceToolResultAccessService sources;
    private final ResourcePolicy resources;
    private final FileAccessService files;
    private final FileContentStorage storage;
    private final ObjectMapper mapper;
    private final ToolResultSettings settings;
    private final FileMapper records;
    private final Clock clock;
    private final TextDocumentCache cache = new TextDocumentCache(64L * 1024 * 1024);

    public ToolResultDocumentService(ToolCallMapper calls, RunMapper runs, ConversationQueryService conversations,
                                     SourceToolResultAccessService sources, ResourcePolicy resources,
                                     FileAccessService files,
                                     FileContentStorage storage, ObjectMapper mapper, ToolResultSettings settings,
                                     FileMapper records, Clock clock) {
        this.calls = calls;
        this.runs = runs;
        this.conversations = conversations;
        this.sources = sources;
        this.resources = resources;
        this.files = files;
        this.storage = storage;
        this.mapper = mapper;
        this.settings = settings;
        this.records = records;
        this.clock = clock;
    }

    public record Document(ToolCallRecord call, TextFileDocument text) {
    }

    public Document open(AuthContext actor, String conversation, String path, String session,
                         Set<String> sharedResults) {
        return open(actor, conversation, path, session, sharedResults, null);
    }

    public Document open(AuthContext actor, String conversation, String path, String session, Set<String> sharedResults,
                         ToolCallControl control) {
        String[] parts = path == null ? new String[0] : path.split("/", -1);
        if (parts.length != 3 || !parts[0].equals("tool-results") || !parts[1].matches("[A-Za-z0-9_-]{1,100}")
            || !Set.of("content.txt", "content.json").contains(parts[2])) {
            throw unavailable();
        }
        String id = parts[1];
        var call = calls.find(actor.enterpriseId(), id, false).orElse(null);
        // 旧结果的引用只有文件编号，仍从实际归属反查原始调用。
        if (call == null) {
            var file = files.ready(actor, id);
            call = calls.forArtifact(actor.enterpriseId(), file.runId(), file.resourceId(), file.id())
                .orElseThrow(ToolResultDocumentService::unavailable);
        }
        require(actor, conversation, call);
        if (session != null && !session.equals(call.frameworkSessionId()) && !sharedResults.contains(
            call.id()) && !sharedResults.contains(id)) {
            throw unavailable();
        }
        if (call.resultRedacted() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_RESULT_NOT_READY", "工具尚未返回可读取的内容。");
        }
        requireStoredContent(actor, call, false);
        var original = call;
        var document = cache.get(call.enterpriseId() + ":" + path + ":" + call.resultRedacted().hashCode(), () -> {
            JsonNode result = result(actor, original, control);
            String text = parts[2].equals("content.txt") ? ToolResultContent.text(result) : result.toPrettyString();
            return new TextFileDocument(path, Utf8Text.revision(path, text), text);
        });
        return new Document(call, document);
    }

    public ToolCallRecord require(AuthContext actor, String conversation, String callId) {
        var call = calls.find(actor.enterpriseId(), callId, false).orElseThrow(ToolResultDocumentService::unavailable);
        require(actor, conversation, call);
        return call;
    }

    public void require(AuthContext actor, String conversation, ToolCallRecord call) {
        if (!actor.enterpriseId().equals(call.enterpriseId()) || !actor.userId()
            .equals(call.actorUserId()) || call.runId() == null) {
            throw unavailable();
        }
        var run = runs.find(actor.enterpriseId(), call.runId(), false)
            .filter(value -> value.userId().equals(actor.userId())
                && value.conversationId().equals(conversation)).orElseThrow(ToolResultDocumentService::unavailable);
        conversations.readable(actor, conversation);
        if (call.resourceVersionId() == null) {
            if (!run.mode().equals("preview") || !Set.of("agent", "workflow").contains(call.resourceKind())) {
                throw unavailable();
            }
            resources.authorize(actor, call.resourceId(), "preview", false, false);
        }
        sources.requireResult(actor, call);
    }

    public JsonNode result(AuthContext actor, ToolCallRecord call) {
        return result(actor, call, null);
    }

    /**
     * 调用者必须先通过日志详情范围校验并记录审计，不授予通用附件访问资格。
     */
    public JsonNode resultAfterLogAuthorization(AuthContext actor, ToolCallRecord call) {
        return loadResult(actor, call, null, true);
    }

    private JsonNode result(AuthContext actor, ToolCallRecord call, ToolCallControl control) {
        return loadResult(actor, call, control, false);
    }

    private JsonNode loadResult(AuthContext actor, ToolCallRecord call, ToolCallControl control,
                                boolean logAuthorized) {
        var result = call.resultRedacted();
        if (result == null) {
            return null;
        }
        if (!result.path("truncated").asBoolean(false) || !result.path("fileId").isTextual()) {
            return result;
        }
        var file = requireStoredContent(actor, call, logAuthorized);
        try (var input = control == null ? storage.open(file) : control.track(storage.open(file))) {
            byte[] bytes = input.readNBytes(settings.maxFileBytes() + 1);
            if (control != null) {
                control.requireActive();
            }
            if (bytes.length > settings.maxFileBytes()) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "结果文件超过当前允许读取的大小。");
            }
            if (bytes.length != file.sizeBytes() || !HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.sha256())) {
                throw new ApiException(HttpStatus.CONFLICT, "FILE_CONTENT_CHANGED",
                    "结果文件与保存时的内容不一致，暂时无法读取。");
            }
            return mapper.readTree(bytes);
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_READ_FAILED",
                "完整结果暂时无法读取，请稍后重试。", failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少文件摘要算法", impossible);
        }
    }

    public FileRecord requireStoredContent(AuthContext actor, ToolCallRecord call, boolean logAuthorized) {
        var result = call.resultRedacted();
        if (result == null || !result.path("truncated").asBoolean(false) || !result.path("fileId").isTextual()) {
            return null;
        }
        var file = logAuthorized ? records.find(actor.enterpriseId(), result.path("fileId").asText(), false)
            .orElseThrow(ToolResultDocumentService::unavailable)
            : files.ready(actor, result.path("fileId").asText());
        if (!file.enterpriseId().equals(actor.enterpriseId()) || !file.ownerUserId()
            .equals(call.actorUserId()) || !file.purpose().equals("artifact")
            || !file.status()
            .equals("ready") || file.deletedAt() != null || file.expiresAt() != null && !file.expiresAt()
            .isAfter(clock.instant())) {
            throw unavailable();
        }
        if (!Objects.equals(file.runId(), call.runId()) || !Objects.equals(file.resourceId(), call.resourceId())
            || !Objects.equals(file.resourceVersionId(), call.resourceVersionId())) {
            throw unavailable();
        }
        if (!file.id().equals(call.id()) && !calls.forArtifact(actor.enterpriseId(), file.runId(), file.resourceId(),
                file.id())
            .map(original -> original.id().equals(call.id())).orElse(false)) {
            throw unavailable();
        }
        if (file.sizeBytes() > settings.maxFileBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "结果文件超过当前允许读取的大小。");
        }
        return file;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "TOOL_RESULT_UNAVAILABLE", "工具结果不存在或已不能读取。");
    }
}
