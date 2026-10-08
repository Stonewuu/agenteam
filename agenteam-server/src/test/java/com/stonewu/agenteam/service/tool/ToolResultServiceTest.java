package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolPayloadMapper;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.file.GeneratedFileService;
import com.stonewu.agenteam.service.file.TextFileDocument;
import com.stonewu.agenteam.service.file.Utf8Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolResultServiceTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());
    private final ToolResultSettings settings = new ToolResultSettings(32, 64, 16, 2, 8, 200, 2, 20);
    private final GeneratedFileService files = mock(GeneratedFileService.class);
    private final RunRecord run = mock(RunRecord.class);
    private final ToolResultService results = new ToolResultService(new ToolPayloadMapper(json), files, json, settings);

    @BeforeEach
    void storeFiles() {
        when(files.toolResult(any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            JsonNode body = call.getArgument(2);
            return new PreparedGeneratedFile("result-id", "enterprise", "user", "resource", "version", "run", "artifact", "工具结果.json",
                "application/json", "enterprise/key", Utf8Text.size(body.toPrettyString()), "checksum");
        });
    }

    @Test
    void savesTwentyThreeKbWithoutMakingTheModelReadItAgain() {
        String text = "a".repeat(23 * 1024);
        var result = results.prepare(run, binding("plugin", "read_url"), json.tree(Map.of("content", text)), List.of(), "call-id", settings.inlineBytes(256000));
        assertEquals(text, result.modelResult().path("content").asText());
        assertEquals("tool-results/call-id/content.txt", result.modelResult().path(ToolResultContent.REFERENCE).path("path").asText());
        assertNotNull(result.file());
        assertTrue(result.redacted().path("truncated").asBoolean());
        assertTrue(Utf8Text.size(result.redacted().path("content").asText()) <= 2048);
    }

    @Test
    void choosesByteLimitFromConfiguredContextWithoutTokenCounting() {
        var source = json.tree(Map.of("content", "文".repeat(14000)));
        var smaller = results.prepare(run, binding("plugin", "read_url"), source, List.of(), "call-id", settings.inlineBytes(256000));
        assertTrue(smaller.modelResult().path("truncated").asBoolean());
        var larger = results.prepare(run, binding("plugin", "read_url"), source, List.of(), "call-id", settings.inlineBytes(1000000));
        assertEquals(source.path("content"), larger.modelResult().path("content"));
    }

    @Test
    void removesSecretsBeforePersistingFullContent() {
        var source = json.tree(Map.of("content", "x".repeat(18000) + "actual-private-key", "password", "hidden-password"));
        results.prepare(run, binding("plugin", "read_url"), source, List.of("actual-private-key"), "call-id", 32768);
        var saved = ArgumentCaptor.forClass(JsonNode.class);
        verify(files).toolResult(eq(run), any(), saved.capture(), eq(settings.maxFileBytes()), eq("call-id"));
        assertFalse(saved.getValue().toString().contains("actual-private-key"));
        assertFalse(saved.getValue().toString().contains("hidden-password"));
        assertTrue(saved.getValue().path("content").asText().startsWith("x".repeat(18000)));
    }

    @Test
    void preservesAuthorizationFieldsWhenLargeKnowledgeAndDataResultsAreSaved() {
        var knowledge = json.tree(Map.of("content", "x".repeat(40000), "citations", List.of(Map.of("documentId", "doc", "generation", 3, "name", "资料"))));
        var k = results.prepare(run, binding("knowledge", "knowledge_search"), knowledge, List.of(), "call-id", 32768);
        assertNotNull(k.file());
        assertEquals(knowledge.path("citations"), k.redacted().path("citations"));
        var data = json.tree(Map.of("rows", List.of(Map.of("内容", "文".repeat(20000))), "fields", List.of(Map.of("name", "内容", "sensitive", true))));
        var d = results.prepare(run, binding("data", "data_query"), data, List.of(), "call-id", 32768);
        assertTrue(d.redacted().path("dataFields").get(0).path("sensitive").asBoolean());
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
    void readResultsCanBeReferencedLaterWithoutCreatingAnotherArtifact() {
        var stored = json.tree(Map.of("content", "资料🙂".repeat(900), "sourceResultIds", List.of("original"), "inputFileIds", List.of("input"), "workspaceVersion", "version"));
        var prepared = results.referenceForHistory("read-call", new ToolResultService.Prepared(stored, stored, null), 32768);
        var reference = prepared.modelResult().path(ToolResultContent.REFERENCE);
        assertEquals("tool-results/read-call/content.txt", reference.path("path").asText());
        assertNull(prepared.file());
        assertEquals(stored, prepared.redacted());
        String text = ToolResultContent.text(stored);
        var document = new TextFileDocument(reference.path("path").asText(), Utf8Text.revision(reference.path("path").asText(), text), text);
        assertEquals(text, document.read(1, null, reference.path("nextCursor").asText(), 2000, 32768).content());
        verifyNoInteractions(files);
    }
}
