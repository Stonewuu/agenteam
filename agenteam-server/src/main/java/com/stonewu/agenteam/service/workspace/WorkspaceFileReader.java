package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 工作文件复用相同的行号、搜索和字节限制，目录变化后旧搜索位置不能继续使用。
 */
@Component
public class WorkspaceFileReader {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceFileReader.class);
    private final ResourceJson json;
    private final ToolResultSettings settings;

    public WorkspaceFileReader(ResourceJson json, ToolResultSettings settings) {
        this.json = json;
        this.settings = settings;
    }

    private record Position(String revision, int index, int line) {
    }

    public JsonNode read(WorkspaceFilesystem files, String version, String tool, JsonNode input, int maximum) {
        if (tool.equals("read_file")) {
            return readFile(files, input, maximum);
        }
        String path = WorkspacePaths.projectLogical(input.path("path").asText("work"));
        var glob = new WorkspaceFileGlob(input.path("glob").asText("*"));
        var candidates = files.files(path, true).stream().filter(file -> glob.matches(file.path())).toList();
        String revision = Utf8Text.revision(version == null ? "empty" : version, json.hash(query(tool, path, input)));
        var position = position(input.path("cursor").asText(null), revision);
        var result = object();
        result.put("complete", true).putNull("nextCursor");
        var items = result.putArray(tool.equals("list_files") ? "items" : "matches");
        int limit = input.path(tool.equals("list_files") ? "limit" : "max_matches").asInt(20);
        for (int index = position.index(); index < candidates.size(); index++) {
            var file = candidates.get(index);
            if (tool.equals("list_files")) {
                items.addObject().put("path", file.path()).put("isDirectory", file.isDirectory())
                    .put("sizeBytes", file.size()).put("modifiedAt", file.modifiedAt());
                if (items.size() > limit || Utf8Text.size(result.toString()) > maximum - 512) {
                    items.remove(items.size() - 1);
                    if (items.isEmpty()) {
                        return tooMany();
                    }
                    return next(result, revision, index, 1);
                }
                continue;
            }
            if (file.isDirectory()) {
                continue;
            }
            try {
                var found = files.document(file.path())
                    .search(input.path("pattern").asText(), input.path("mode").asText("literal").equals("regex"),
                        input.path("ignore_case").asBoolean(false), index == position.index() ? position.line() : 1,
                        limit,
                        input.path("before").asInt(2), input.path("after").asInt(2),
                        Math.max(256, Math.min(settings.readBytes(), (maximum - 1024) / 6)), () -> {
                            files.requireActive();
                            return false;
                        });
                for (var match : found.matches()) {
                    items.add(json.tree(match));
                    if (items.size() > limit || Utf8Text.size(result.toString()) > maximum - 512) {
                        items.remove(items.size() - 1);
                        if (items.isEmpty()) {
                            return tooMany();
                        }
                        return next(result, revision, index, match.line());
                    }
                }
                if (!found.complete()) {
                    return next(result, revision, index, found.nextLine());
                }
            } catch (ApiException failure) {
                if (!failure.code().equals("WORKSPACE_BINARY_FILE")) {
                    throw failure;
                }
                LOG.debug("搜索跳过非文本文件，工作文件版本 {}", version, failure);
            }
        }
        return result;
    }

    private ObjectNode query(String tool, String path, JsonNode input) {
        var query = object().put("tool", tool).put("path", path).put("glob", input.path("glob").asText("*"))
            .put("limit", input.path(tool.equals("list_files") ? "limit" : "max_matches").asInt(20));
        if (tool.equals("grep_files")) {
            query.put("pattern", input.path("pattern").asText()).put("mode", input.path("mode").asText("literal"))
                .put("ignore_case", input.path("ignore_case").asBoolean(false))
                .put("before", input.path("before").asInt(2)).put("after", input.path("after").asInt(2));
        }
        return query;
    }

    private JsonNode readFile(WorkspaceFilesystem files, JsonNode input, int maximum) {
        var document = files.document(input.path("path").asText());
        int bytes = settings.readBytes();
        while (true) {
            var page = json.tree(document.read(input.path("start_line").asInt(1),
                input.hasNonNull("end_line") ? input.path("end_line").asInt() : null,
                input.path("cursor").asText(null), settings.readLines(), bytes));
            if (Utf8Text.size(page.toString()) <= maximum) {
                return page;
            }
            if (bytes <= 4) {
                return tooMany();
            }
            bytes = Math.max(4, bytes / 2);
        }
    }

    private Position position(String cursor, String revision) {
        if (cursor == null) {
            return new Position(revision, 0, 1);
        }
        try {
            if (cursor.length() > 2048) {
                throw new IllegalArgumentException("续读位置过长");
            }
            var value = json.read(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            int index = value.path("index").asInt(-1), line = value.path("line").asInt();
            if (!revision.equals(value.path("revision").asText()) || index < 0 || line < 1) {
                throw new IllegalArgumentException("文件或搜索条件已改变");
            }
            return new Position(revision, index, line);
        } catch (RuntimeException cause) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WORKSPACE_CURSOR_CHANGED",
                "工作文件或搜索条件已改变，请重新开始查找。", cause);
        }
    }

    private ObjectNode next(ObjectNode result, String revision, int index, int line) {
        return result.put("complete", false).put("nextCursor", Base64.getUrlEncoder().withoutPadding().encodeToString(
            json.write(json.tree(new Position(revision, index, line))).getBytes(StandardCharsets.UTF_8)));
    }

    private ObjectNode object() {
        return (ObjectNode) json.tree(Map.of());
    }

    private ObjectNode tooMany() {
        return object().put("isError", true).put("content", "本轮同时读取的内容过多，请减少并行调用后继续读取。");
    }
}
