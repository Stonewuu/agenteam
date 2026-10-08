package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.agent.entity.AgentEventRecord;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 恢复事件未携带调用别名时，使用已保存工具编号区分合集中的同名工具。
 */
class AgentToolRecordEventTest {
    @Test
    void toolEventsDistinguishGeneratingArgumentsFromWaitingAndExecuting() {
        var json = new ObjectMapper();
        var now = Instant.parse("2026-09-19T00:00:00Z");
        var run = new RunRecord("run", "enterprise", "conversation", "user", "input", "output", "version", "interactive", "running",
            json.createObjectNode(), 1, 1, 1, false, 3, now, null, null, null, null, null, now);
        var mapper = new AgentPublicEventMapper(run, "attempt", json, List.of());
        var definition = new ToolDefinition("execute", "执行命令", "a".repeat(64), json.createObjectNode(), null,
            json.createObjectNode().put("title", "执行命令"), "write", false, false, false, List.of(), 30);
        var binding = new ExecutionToolBinding("resource", "version", "agent", "文件", null, definition, json.createObjectNode().put("workspaceTool", true));
        mapper.platformTools(Map.of(binding.alias(), binding), (session, id) -> null);
        var expected = List.of(
            List.of("TOOL_CALL_START", "pending", "running", "pending"),
            List.of("TOOL_CALL_DELTA", "pending", "running", "pending"),
            List.of("TOOL_CALL_END", "pending", "completed", "pending"),
            List.of("TOOL_RESULT_START", "running", "completed", "running"),
            List.of("TOOL_RESULT_END", "completed", "completed", "completed"));
        long sequence = 0;
        for (var event : expected) {
            mapper.accept(new AgentEventRecord("event-" + ++sequence, "conversation", "run", sequence, event.getFirst(), null,
                "reply", null, "call", binding.alias(), null, null, null, Map.of(), Map.of(), null, null, false, null, null));
            var block = mapper.presentation().blocks().values().iterator().next();
            assertEquals(event.get(1), block.status(), event.getFirst());
            assertEquals(event.get(2), block.tool().callStatus(), event.getFirst());
            assertEquals(event.get(3), block.tool().resultStatus(), event.getFirst());
        }
    }

    @Test
    void savedToolIdIdentifiesTheCorrectDefinitionWhenNamesAreEqual() {
        var json = new ObjectMapper();
        var now = Instant.parse("2026-09-18T00:00:00Z");
        var run = new RunRecord("run", "enterprise", "conversation", "user", "input", "output", "version", "interactive", "running",
            json.createObjectNode(), 1, 1, 1, false, 3, now, null, null, null, null, null, now);
        var mapper = new AgentPublicEventMapper(run, "attempt", json, List.of());
        var bindings = new LinkedHashMap<String, ExecutionToolBinding>();
        for (String source : List.of("甲", "乙")) {
            var definition = new ToolDefinition("lookup", "查询来源", "a".repeat(64), json.createObjectNode(), null,
                json.createObjectNode().put("title", "查询来源" + source), "read", false, false, false, List.of(), 30);
            var binding = new ExecutionToolBinding("plugin", "version", "plugin", "工具合集", source, definition, json.createObjectNode());
            bindings.put(binding.alias(), binding);
        }
        var call = mock(ToolCallRecord.class);
        when(call.resourceId()).thenReturn("plugin");
        when(call.resourceKind()).thenReturn("plugin");
        when(call.resourceVersionId()).thenReturn("version");
        when(call.pluginToolId()).thenReturn("乙");
        when(call.toolName()).thenReturn("lookup");
        when(call.requestRedacted()).thenReturn(json.createObjectNode());
        mapper.platformTools(bindings, (session, id) -> call);
        mapper.accept(new AgentEventRecord("event", "conversation", "run", 1, "TOOL_RESULT_START", null,
            "reply", null, "call", null, null, null, null, Map.of(), Map.of(), null, null, false, null, null));
        var block = mapper.presentation().blocks().values().iterator().next();
        assertEquals("工具合集 · 查询来源乙", block.label());
    }
}
