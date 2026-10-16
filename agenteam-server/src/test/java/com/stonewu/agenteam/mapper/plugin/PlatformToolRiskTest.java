package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformToolRiskTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());

    @Test
    void toolDefinitionsKeepVerifiedRiskAndStableInvocationStructures() {
        var definitions = new PlatformToolDefinitions(json);
        for (var tools : List.of(definitions.todos(), definitions.schedules())) {
            for (var tool : tools) {
                if (List.of("todo_delete", "schedule_delete").contains(tool.name())) {
                    assertEquals("destructive", tool.operationClass());
                    var invocationHash = json.hash(json.tree(Map.of("name", tool.name(), "inputSchema", tool.inputSchema(), "outputSchema", tool.outputSchema(), "operationClass", "write")));
                    assertEquals(invocationHash, tool.schemaHash(), "固定版本调用结构应与写操作的身份一致");
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

    @Test
    void approvalRulesCoverEveryRiskAndKeepScheduledRunsOnDefaultPolicy() {
        var operations = List.of("read", "write", "destructive", "unknown");
        var expected = Map.of(
            ToolApprovalPolicy.DEFAULT, List.of(false, true, true, true),
            ToolApprovalPolicy.AUTO_APPROVE, List.of(false, false, true, true),
            ToolApprovalPolicy.FULL_ACCESS, List.of(false, false, false, false));
        var run = mock(RunRecord.class);
        var config = new ObjectMapper().createObjectNode();
        when(run.mode()).thenReturn("interactive");
        when(run.executionConfig()).thenReturn(config);
        for (var policy : ToolApprovalPolicy.values()) {
            config.put("approvalPolicy", policy.value());
            assertSame(policy, ToolApprovalPolicy.from(policy.value()));
            assertSame(policy, ToolApprovalPolicy.forRun(run));
            for (int i = 0; i < operations.size(); i++) {
                String operation = operations.get(i);
                assertEquals(expected.get(policy).get(i), policy.requiresApproval(operation),
                    policy.value() + ": " + operation);
            }
        }
        assertSame(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicy.from(null));
        assertSame(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicy.from("anything"));
        config.remove("approvalPolicy");
        assertSame(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicy.forRun(run));
        config.put("approvalPolicy", "full_access");
        when(run.mode()).thenReturn("scheduled");
        assertSame(ToolApprovalPolicy.DEFAULT, ToolApprovalPolicy.forRun(run));
    }
}
