package com.stonewu.agenteam.service.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.configuration.tool.WorkspaceSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFileReaderTest {
    @TempDir
    Path root;
    private final ResourceJson json = new ResourceJson(new ObjectMapper());

    @Test
    void rangeSearchAndDirectoryContinuationUseRealStableFilePositions() throws Exception {
        Files.createDirectories(root.resolve("work"));
        var files = new WorkspaceFilesystem(root, new WorkspaceSettings(root.toString(), "python:3.13-slim", true, 16, 1, 40, 256, 1, 32, 30, "none"), () -> {
        }, true);
        for (int index = 0; index < 25; index++) {
            files.write(RuntimeContext.empty(), "work/file-" + String.format("%02d", index) + ".txt", "第一行🙂\n内容\n最后一行：答案" + index);
        }
        var reader = new WorkspaceFileReader(json, new ToolResultSettings(32, 64, 16, 2, 8, 200, 2, 20));
        var query = new ObjectMapper().createObjectNode().put("path", "work").put("limit", 20);
        var page = reader.read(files, "saved-version", "list_files", query, 8192);
        assertEquals(20, page.path("items").size());
        assertFalse(page.path("complete").asBoolean());
        var next = new ObjectMapper().createObjectNode().put("cursor", page.path("nextCursor").asText()).put("limit", 20).put("path", "work");
        assertEquals(5, reader.read(files, "saved-version", "list_files", next, 8192).path("items").size());
        next.remove("limit");
        assertEquals(5, reader.read(files, "saved-version", "list_files", next, 8192).path("items").size(), "参数顺序及省略默认值不改变续读条件");
        assertThrows(ApiException.class, () -> reader.read(files, "changed-version", "list_files", next, 8192));
        var found = reader.read(files, "saved-version", "grep_files", json.tree(Map.of("path", "work", "pattern", "答案2[0-4]", "mode", "regex")), 8192);
        assertEquals(5, found.path("matches").size());
        assertTrue(found.path("complete").asBoolean());
        var hit = found.path("matches").get(4);
        var tail = reader.read(files, "saved-version", "read_file", json.tree(Map.of("path", hit.path("path").asText(), "start_line", hit.path("line").asInt(), "end_line", hit.path("line").asInt())), 8192);
        assertEquals("最后一行：答案24", tail.path("content").asText());
        assertTrue(Utf8Text.size(tail.toString()) <= 8192);
    }
}
