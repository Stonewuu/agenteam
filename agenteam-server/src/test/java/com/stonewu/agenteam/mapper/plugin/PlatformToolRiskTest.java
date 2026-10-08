package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformToolRiskTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());

    @Test
    void deletionIsSensitiveWithoutInvalidatingPublishedCallStructures() {
        var definitions = new PlatformToolDefinitions(json);
        for (var tools : List.of(definitions.todos(), definitions.schedules())) {
            for (var tool : tools) {
                if (List.of("todo_delete", "schedule_delete").contains(tool.name())) {
                    assertEquals("destructive", tool.operationClass());
                    var oldHash = json.hash(json.tree(Map.of("name", tool.name(), "inputSchema", tool.inputSchema(), "outputSchema", tool.outputSchema(), "operationClass", "write")));
                    assertEquals(oldHash, tool.schemaHash(), "既有固定版本的调用结构仍应可用");
                }
                if (List.of("todo_create", "schedule_update").contains(tool.name())) {
                    assertEquals("write", tool.operationClass());
                }
                if (List.of("todo_get", "schedule_list").contains(tool.name())) {
                    assertEquals("read", tool.operationClass());
                }
            }
        }
    }

    @Test
    void remoteHintsCannotMakeAnUnknownToolAutomaticallyApproved() {
        var mapper = new PluginToolDefinitionMapper(json, new ToolSchemaValidation());
        var result = mapper.remote(List.of(json.tree(Map.of("name", "remote_action", "inputSchema", Map.of("type", "object"),
            "annotations", Map.of("readOnlyHint", true, "destructiveHint", false)))), 30);
        assertEquals("unknown", result.getFirst().operationClass());
    }
}
