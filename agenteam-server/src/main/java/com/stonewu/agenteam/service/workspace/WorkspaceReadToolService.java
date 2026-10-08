package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.service.execution.ExecutionAccessService;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.ToolResultDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读文件工具通过当前执行身份访问保存结果，返回内容有真实的续读位置。
 */
@Service
public class WorkspaceReadToolService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceReadToolService.class);
    private final ExecutionAccessService access;
    private final ToolResultDocumentService documents;
    private final ToolCallMapper calls;
    private final ResourceJson json;
    private final ToolResultSettings settings;

    public WorkspaceReadToolService(ExecutionAccessService access, ToolResultDocumentService documents,
                                    ToolCallMapper calls,
                                    ResourceJson json, ToolResultSettings settings) {
        this.access = access;
        this.documents = documents;
        this.calls = calls;
        this.json = json;
        this.settings = settings;
    }

    private record Scope(AuthContext actor, RunRecord run, String session, Set<String> shared, ToolCallControl control,
                         long deadline, int maximum) {
        void requireActive() {
            control.requireActive();
            if (System.nanoTime() >= deadline) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "FILE_READ_TIMEOUT", "文件读取超时，请缩小搜索范围。");
            }
        }

        boolean allows(ToolCallRecord call) {
            return session.equals(call.frameworkSessionId()) || shared.contains(call.id());
        }
    }

    private record Position(String hash, String afterId, String fileId, int line) {
    }

    public JsonNode invoke(RunRecord run, String session, String name, JsonNode input, Duration timeout,
                           ToolCallControl control,
                           int maximum, Set<String> shared) {
        var scope = new Scope(access.actor(run), run, session, shared, control, System.nanoTime() + timeout.toNanos(),
            maximum);
        scope.requireActive();
        control.beforeSend();
        JsonNode result = switch (name) {
            case "read_file" -> read(scope, input);
            case "list_files" -> list(scope, input);
            case "grep_files" -> search(scope, input);
            default ->
                throw new ApiException(HttpStatus.NOT_FOUND, "WORKSPACE_TOOL_UNAVAILABLE", "当前文件操作不可用。");
        };
        return Utf8Text.size(result.toString()) <= maximum ? result : tooMany();
    }

    private JsonNode read(Scope scope, JsonNode input) {
        var document = documents.open(scope.actor(), scope.run().conversationId(), input.path("path").asText(),
            scope.session(), scope.shared(), scope.control());
        int bytes = settings.readBytes();
        while (true) {
            scope.requireActive();
            var slice = document.text().read(input.path("start_line").asInt(1),
                input.hasNonNull("end_line") ? input.path("end_line").asInt() : null,
                input.path("cursor").asText(null), settings.readLines(), bytes);
            var result = (ObjectNode) json.tree(slice);
            result.putArray("sourceResultIds").add(document.call().id());
            if (Utf8Text.size(result.toString()) <= scope.maximum()) {
                return result;
            }
            if (bytes <= 4) {
                return tooMany();
            }
            bytes = Math.max(4, bytes / 2);
        }
    }

    private JsonNode list(Scope scope, JsonNode input) {
        String root = root(input), hash = queryHash(input), after = decode(input.path("cursor").asText(null),
            hash).afterId();
        var glob = new WorkspaceFileGlob(input.path("glob").asText("*"));
        int limit = input.path("limit").asInt(20);
        var result = object();
        var items = result.putArray("items");
        result.putNull("nextCursor");
        var candidates = calls.resultsForConversation(scope.actor().enterpriseId(), scope.actor().userId(),
            scope.run().conversationId(), after, 100);
        for (var call : candidates) {
            scope.requireActive();
            String path = candidatePath(call, root, glob);
            if (path == null || !scope.allows(call) || !readable(scope, call)) {
                after = call.id();
                continue;
            }
            var row = object().put("path", path).put("source", call.toolName())
                .put("rawPath", ToolResultContent.path(call.id(), false));
            var saved = call.resultRedacted();
            if (saved.path("truncated").asBoolean(false)) {
                if (saved.has("rawSizeBytes")) {
                    row.put("sizeBytes", saved.path(path.endsWith(".json") ? "rawSizeBytes" : "sizeBytes").asLong());
                }
                if (path.endsWith(".txt") && saved.has("totalLines")) {
                    row.put("totalLines", saved.path("totalLines").asLong());
                }
            } else {
                String text = path.endsWith(".txt") ? ToolResultContent.text(saved) : saved.toPrettyString();
                row.put("sizeBytes", Utf8Text.size(text))
                    .put("totalLines", text.isEmpty() ? 0 : text.chars().filter(c -> c == '\n').count() + 1);
            }
            items.add(row);
            if (items.size() > limit || Utf8Text.size(result.toString()) > scope.maximum() - 512) {
                items.remove(items.size() - 1);
                if (items.isEmpty()) {
                    return tooMany();
                }
                result.put("nextCursor", encode(new Position(hash, after, null, 1)));
                return result;
            }
            after = call.id();
        }
        if (candidates.size() == 100) {
            result.put("nextCursor", encode(new Position(hash, after, null, 1)));
        }
        return result;
    }

    private JsonNode search(Scope scope, JsonNode input) {
        String root = root(input), hash = queryHash(input);
        Position position = decode(input.path("cursor").asText(null), hash);
        var glob = new WorkspaceFileGlob(input.path("glob").asText("*"));
        var result = object();
        var matches = result.putArray("matches");
        var origins = result.putArray("sourceResultIds");
        result.putNull("nextCursor").put("complete", true);
        int maximumMatches = input.path("max_matches").asInt(20);
        boolean exact = root.endsWith("/content.txt") || root.endsWith("/content.json");
        List<ToolCallRecord> candidates;
        if (exact) {
            candidates = List.of(
                documents.open(scope.actor(), scope.run().conversationId(), root, scope.session(), scope.shared(),
                    scope.control()).call());
        } else {
            candidates = calls.resultsForConversation(scope.actor().enterpriseId(), scope.actor().userId(),
                scope.run().conversationId(), position.afterId(), 100);
        }
        String after = position.afterId();
        for (var call : candidates) {
            scope.requireActive();
            String path = exact ? root : candidatePath(call, root, glob);
            if (path == null || !scope.allows(call) || !readable(scope, call)) {
                after = call.id();
                continue;
            }
            origins.add(call.id());
            if (Utf8Text.size(result.toString()) > scope.maximum() - 512) {
                origins.remove(origins.size() - 1);
                if (origins.isEmpty()) {
                    return tooMany();
                }
                return next(result, new Position(hash, after, null, 1));
            }
            var document = documents.open(scope.actor(), scope.run().conversationId(), path, scope.session(),
                scope.shared(), scope.control());
            int start = call.id().equals(position.fileId()) ? position.line() : 1;
            var page = document.text()
                .search(input.path("pattern").asText(), input.path("mode").asText("literal").equals("regex"),
                    input.path("ignore_case").asBoolean(false), start, maximumMatches, input.path("before").asInt(2),
                    input.path("after").asInt(2),
                    Math.max(256, Math.min(settings.readBytes(), (scope.maximum() - 1024) / 6)), () -> {
                        scope.requireActive();
                        return false;
                    });
            for (var match : page.matches()) {
                matches.add(json.tree(match));
                if (matches.size() > maximumMatches || Utf8Text.size(result.toString()) > scope.maximum() - 512) {
                    matches.remove(matches.size() - 1);
                    if (matches.isEmpty()) {
                        return tooMany();
                    }
                    return next(result, new Position(hash, after, call.id(), match.line()));
                }
            }
            if (!page.complete()) {
                return next(result, new Position(hash, after, call.id(), page.nextLine()));
            }
            after = call.id();
        }
        if (!exact && candidates.size() == 100) {
            return next(result, new Position(hash, after, null, 1));
        }
        return result;
    }

    private boolean readable(Scope scope, ToolCallRecord call) {
        try {
            documents.require(scope.actor(), scope.run().conversationId(), call);
            documents.requireStoredContent(scope.actor(), call, false);
            return true;
        } catch (ApiException failure) {
            if (!Set.of(403, 404).contains(failure.getStatusCode().value())) {
                throw failure;
            }
            LOG.debug("结果文件已不能列出，执行编号 {}，调用编号 {}", scope.run().id(), call.id(), failure);
            return false;
        }
    }

    private String candidatePath(ToolCallRecord call, String root, WorkspaceFileGlob glob) {
        if (Set.of("agent", "workflow").contains(call.resourceKind()) && WorkspaceToolDefinitions.READ_TOOLS.contains(
            call.toolName())) {
            return null;
        }
        var value = call.resultRedacted();
        String path = ToolResultContent.path(call.id(),
            value.has("rawPath") ? value.path("path").asText().endsWith(".txt") : ToolResultContent.hasText(value));
        String raw = ToolResultContent.path(call.id(), false);
        if (path.startsWith(root) && glob.matches(path)) {
            return path;
        }
        return raw.startsWith(root) && glob.matches(raw) ? raw : null;
    }

    private String root(JsonNode input) {
        String path = input.path("path").asText("tool-results/");
        if (path.equals("tool-results")) {
            return "tool-results/";
        }
        if (!path.matches("tool-results/(?:[A-Za-z0-9_-]{1,100}/(?:content\\.(?:txt|json))?)?")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_PATH_INVALID", "请选择当前任务的结果文件。");
        }
        return path;
    }

    private String queryHash(JsonNode input) {
        return json.hash(json.tree(Map.of("path", root(input), "pattern", input.path("pattern").asText(), "mode",
            input.path("mode").asText("literal"),
            "ignoreCase", input.path("ignore_case").asBoolean(false), "glob", input.path("glob").asText("*"))));
    }

    private Position decode(String value, String hash) {
        if (value == null) {
            return new Position(hash, null, null, 1);
        }
        try {
            if (value.length() > 2048) {
                throw new IllegalArgumentException("续读位置过长");
            }
            var saved = json.read(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
            String after = saved.path("afterId").asText(null), file = saved.path("fileId").asText(null);
            int line = saved.path("line").asInt(1);
            if (!hash.equals(saved.path("hash").asText()) || after != null && !after.matches("[A-Za-z0-9_-]{1,100}")
                || file != null && !file.matches("[A-Za-z0-9_-]{1,100}") || line < 1) {
                throw new IllegalArgumentException("搜索条件或位置已经变化");
            }
            return new Position(hash, after, file, line);
        } catch (RuntimeException failure) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_CURSOR_INVALID", "续读位置无效，请重新开始本次查找。",
                failure);
        }
    }

    private String encode(Position value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.write(json.tree(value)).getBytes(StandardCharsets.UTF_8));
    }

    private ObjectNode next(ObjectNode result, Position position) {
        result.put("complete", false).put("nextCursor", encode(position));
        return result;
    }

    private ObjectNode object() {
        return (ObjectNode) json.tree(Map.of());
    }

    private ObjectNode tooMany() {
        return object().put("isError", true).put("content", "本轮同时读取的内容过多，请减少并行调用后继续读取。");
    }
}
