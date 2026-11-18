package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
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
import java.io.FilterInputStream;
import java.io.InputStream;
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
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_RESULT_NOT_READY", Messages.text("error.tool.toolResultDocumentService.open.1"));
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
     * 调用者必须先检查用户能否查看该日志详情，并记录审计日志；本方法不会授予其他附件的访问权限。
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
        var factory = mapper.getFactory().copy();
        factory.setStreamReadConstraints(factory.streamReadConstraints().rebuild()
            .maxStringLength((int) Math.min(Integer.MAX_VALUE, settings.maxFileBytes())).build());
        try (var input = new VerifiedInput(control == null ? storage.open(file) : control.track(storage.open(file)),
            settings.maxFileBytes(), control); var parser = factory.createParser(input)) {
            parser.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
            JsonNode loaded = mapper.readTree(parser);
            byte[] buffer = new byte[8192];
            while (input.read(buffer) != -1) {
                // 校验整个文件，包括解析器没有消费的尾部空白。
            }
            if (control != null) {
                control.requireActive();
            }
            if (input.count != file.sizeBytes() || !HexFormat.of().formatHex(input.digest.digest()).equals(file.sha256())) {
                throw new ApiException(HttpStatus.CONFLICT, "FILE_CONTENT_CHANGED",
                    Messages.text("error.tool.toolResultDocumentService.loadResult.2"));
            }
            return loaded;
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_READ_FAILED",
                Messages.text("error.tool.toolResultDocumentService.loadResult.3"), failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(Messages.text("error.file.documentParserProcess.copySource.1"), impossible);
        }
    }

    /** 流式解析完整结果并核对长度与摘要，不按配置值预先分配巨大字节数组。 */
    private static final class VerifiedInput extends FilterInputStream {
        private final long maximum;
        private final ToolCallControl control;
        private final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        private long count;

        private VerifiedInput(InputStream input, long maximum, ToolCallControl control) throws NoSuchAlgorithmException {
            super(input);
            this.maximum = maximum;
            this.control = control;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (control != null) {
                control.requireActive();
            }
            int read = in.read(bytes, offset, length);
            if (read > 0) {
                if (read > maximum - count) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                        Messages.text("error.tool.toolResultDocumentService.loadResult.1"));
                }
                count += read;
                digest.update(bytes, offset, read);
            }
            return read;
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
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", Messages.text("error.tool.toolResultDocumentService.loadResult.1"));
        }
        return file;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "TOOL_RESULT_UNAVAILABLE", Messages.text("error.tool.toolResultDocumentService.unavailable.1"));
    }
}
