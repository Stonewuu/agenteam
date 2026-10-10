package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.file.Utf8Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolResultServiceTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());
    private final ToolResultSettings settings = new ToolResultSettings(32, 64, 2, 8, 200, 2, 20);
    private final RunRecord run = mock(RunRecord.class);
    private final ToolResultService results = new ToolResultService(new ToolPayloadMapper(json), settings);

    @Test
    void returnsTwentyThreeKbWithoutFilesOrReferences() {
        String text = "a".repeat(23 * 1024);
        var result = results.prepare(run, binding("plugin", "read_url"), json.tree(Map.of("content", text)), List.of(), "call-id", settings.inlineBytes(256000));
        assertEquals(text, result.modelResult().path("content").asText());
        assertFalse(result.modelResult().has(ToolResultContent.REFERENCE));
        assertNull(result.file());
        assertEquals(result.modelResult(), result.redacted());
    }

    @Test
    void choosesByteLimitFromConfiguredContextWithoutTokenCounting() {
        var source = json.tree(Map.of("content", "文".repeat(14000)));
        var smaller = results.prepare(run, binding("plugin", "read_url"), source, List.of(), "call-id", settings.inlineBytes(256000));
        assertTrue(smaller.modelResult().path("truncated").asBoolean());
        assertTrue(Utf8Text.size(smaller.modelResult().path("content").asText()) > 8192);
        assertEquals(source, smaller.redacted());
        assertNull(smaller.file());
        assertFalse(smaller.modelResult().has("fileId"));
        var larger = results.prepare(run, binding("plugin", "read_url"), source, List.of(), "call-id", settings.inlineBytes(1000000));
        assertEquals(source.path("content"), larger.modelResult().path("content"));
    }

    @Test
    void removesSecretsBeforePersistingFullContent() {
        var source = json.tree(Map.of("content", "x".repeat(18000) + "actual-private-key", "password", "hidden-password"));
        var result = results.prepare(run, binding("plugin", "read_url"), source, List.of("actual-private-key"), "call-id", 32768);
        for (var value : List.of(result.modelResult(), result.redacted())) {
            assertFalse(value.toString().contains("actual-private-key"));
            assertFalse(value.toString().contains("hidden-password"));
            assertTrue(value.path("content").asText().startsWith("x".repeat(18000)));
        }
    }

    @Test
    void preservesAuthorizationFieldsWhenLargeKnowledgeAndDataResultsAreSaved() {
        var knowledge = json.tree(Map.of("content", "x".repeat(40000), "citations", List.of(Map.of("documentId", "doc", "generation", 3, "name", "资料"))));
        var k = results.prepare(run, binding("knowledge", "knowledge_search"), knowledge, List.of(), "call-id", 32768);
        assertNull(k.file());
        assertEquals(knowledge.path("citations"), k.redacted().path("citations"));
        var data = json.tree(Map.of("rows", List.of(Map.of("内容", "文".repeat(20000))), "fields", List.of(Map.of("name", "内容", "sensitive", true))));
        var d = results.prepare(run, binding("data", "data_query"), data, List.of(), "call-id", 32768);
        assertEquals(data, d.redacted());
        assertTrue(d.redacted().path("fields").get(0).path("sensitive").asBoolean());
    }

    @Test
    void boundsCombinedOutputsIncludingJsonEscapesAndReferences() {
        int calls = 100, maximum = ToolResultBudget.perCall(settings, 256000, calls), total = 0;
        var source = json.tree(Map.of("content", "\u0001\"文🙂".repeat(3000), "url", "https://example.test/" + "x".repeat(2000)));
        for (int index = 0; index < calls; index++) {
            var result = results.prepare(run, binding("plugin", "read_url"), source, List.of(), "call-" + index, maximum);
            int size = Utf8Text.size(result.modelResult().toString());
            assertTrue(size <= maximum, "序列化后的完整结构不能超过分配大小");
            assertTrue(result.modelResult().path("path").asText().startsWith("tool-results/"));
            assertEquals(source, result.redacted());
            assertNull(result.file());
            total += size;
        }
        assertTrue(total <= 64 * 1024);
    }

    @Test
    void shortenedPreviewContinuesAtExactlyTheFirstUnreadCharacter() {
        String content = "文🙂\n".repeat(10000);
        var reference = ToolResultContent.reference("call-id", "file-id", json.tree(Map.of("content", content)), 100000, 2048);
        var limited = ToolResultContent.fitReference(reference, 1024);
        var file = new TextFileDocument(limited.path("path").asText(), Utf8Text.revision(limited.path("path").asText(), content), content);
        StringBuilder reconstructed = new StringBuilder(limited.path("content").asText());
        String cursor = limited.path("nextCursor").asText();
        assertFalse(cursor.isEmpty());
        do {
            var part = file.read(1, null, cursor, 200, 8192);
            reconstructed.append(part.content());
            cursor = part.nextCursor();
        } while (cursor != null);
        assertEquals(content, reconstructed.toString());
    }

    private ExecutionToolBinding binding(String kind, String name) {
        var definition = new ToolDefinition(name, "读取", "hash", json.tree(Map.of()), null, json.tree(Map.of()), "read", false, false, true, List.of(), 30);
        return new ExecutionToolBinding("resource", "version", kind, "资料", null, definition, json.tree(Map.of()));
    }

    @Test
    void readResultsStayAvailableWithoutAddingHistoryReferences() {
        var stored = json.tree(Map.of("content", "资料🙂".repeat(900), "sourceResultIds", List.of("original"), "inputFileIds", List.of("input"), "workspaceVersion", "version"));
        var prepared = results.prepare(run, binding("plugin", "read_url"), stored, List.of(), "read-call", 32768);
        assertEquals(stored, prepared.modelResult());
        assertFalse(prepared.modelResult().has(ToolResultContent.REFERENCE));
        assertNull(prepared.file());
        assertEquals(stored, prepared.redacted());
    }
}
