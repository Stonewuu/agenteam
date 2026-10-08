package com.stonewu.agenteam.mapper.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportPayloadMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ExportPayloadMapper payloads = new ExportPayloadMapper(json);

    @Test
    void previousConversationPayloadKeepsConditionsAndCompletedMetadata() {
        var value = payloads.read("""
            {"definition":{"type":"conversation","conversationId":"conversation-1","audit":null,"toolCalls":null},
             "actorIds":["user-1"],"rowCount":2,"snapshotAt":"2026-09-29T00:00:00Z","expiresAt":"2026-09-30T00:00:00Z"}
            """);
        assertThat(value.definition().type()).isEqualTo("conversation");
        assertThat(value.definition().parameters()).containsOnlyKeys("conversationId");
        assertThat(value.definition().parameters().get("conversationId").asText()).isEqualTo("conversation-1");
        assertThat(value.actorIds()).containsExactly("user-1");
        assertThat(value.rowCount()).isEqualTo(2);
        assertThat(value.snapshotAt()).isEqualTo("2026-09-29T00:00:00Z");
        assertThat(value.expiresAt()).isEqualTo("2026-09-30T00:00:00Z");
        assertThat(payloads.read(payloads.write(value))).isEqualTo(value);
    }

    @Test
    void previousExtensionPayloadPreservesItsFieldsWithoutLoadingItsModel() {
        var value = payloads.read("""
            {"definition":{"type":"audit","conversationId":null,
             "audit":{"from":"2026-09-28T00:00:00Z","to":"2026-09-29T00:00:00Z","action":"resource.update"},
             "toolCalls":null},"actorIds":[],"rowCount":null,"snapshotAt":null,"expiresAt":null}
            """);
        assertThat(value.definition().parameters()).containsOnlyKeys("audit");
        assertThat(value.definition().parameters().get("audit").path("action").asText()).isEqualTo("resource.update");
        assertThat(payloads.read(payloads.write(value))).isEqualTo(value);
    }

    @Test
    void definitionsCopyCallerParametersBeforeTheJobIsSaved() {
        var condition = json.createObjectNode().put("status", "succeeded");
        var definition = new ExportDefinition("tool_calls", Map.of("toolCalls", condition));
        condition.put("status", "failed");
        assertThat(definition.parameters().get("toolCalls").path("status").asText()).isEqualTo("succeeded");
    }
}
