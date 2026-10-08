package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunApprovalRecord;
import com.stonewu.agenteam.model.execution.response.RunApprovalView;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalSummaryMapperTest {
    private final ResourceJson json = new ResourceJson(new ObjectMapper());
    private final ToolApprovalSummaryMapper mapper = new ToolApprovalSummaryMapper();

    @Test
    void workspaceOperationsKeepActualArgumentsWithoutModelInstructions() {
        var arguments = json.tree(Map.of("path", "outputs/demo.html", "old_text", "原文", "new_text", "新内容", "replace_all", false));
        for (String name : List.of("write_file", "edit_file", "execute", "export_file")) {
            var binding = binding(name, "agent", "write", Map.of("workspaceTool", true));
            var result = mapper.map(binding, arguments);
            assertEquals("", result.description(), name);
            assertEquals("文件：outputs/demo.html", result.target());
            assertEquals(arguments.toString(), result.content());
            assertEquals("模型需要先读取文件，再按 old_text 和 replace_all 规则调用。", binding.definition().description());
        }
    }

    @Test
    void externalWriteKeepsItsImpactNoticeWithoutCopyingModelInstructions() {
        var result = mapper.map(binding("send", "plugin", "write", Map.of()), json.tree(Map.of("recipient", "user@example.test", "text", "待发送正文")));
        assertEquals("此工具可能修改外部数据，请核对全部参数后再决定。", result.description());
        assertEquals("收件人：user@example.test", result.target());
        assertFalse(result.description().contains("old_text"));
    }

    @Test
    void readOnlyToolHasNoWriteNoticeAndPlatformWriteKeepsItsActualScope() {
        assertEquals("", mapper.map(binding("read", "plugin", "read", Map.of()), json.tree(Map.of())).description());
        var result = mapper.map(binding("todo_update", "plugin", "write", Map.of("pluginType", "builtin", "builtinCode", "todo_management")), json.tree(Map.of()));
        assertEquals("确认后将修改平台中的待办或定时任务，请核对操作内容。", result.description());
    }

    @Test
    void savedApprovalsExposeTheirRealOriginWithoutChangingStoredSummaryOrRequestHash() {
        var summary = new RunApprovalView.Summary("标题", "目标", "以前保存的工具说明", "原始参数", "影响说明");
        var time = Instant.parse("2026-09-19T12:00:00Z");
        for (boolean tool : List.of(true, false)) {
            var record = new RunApprovalRecord("approval", "enterprise", "run", "step", tool ? "call" : null, "user", "a".repeat(64), summary, "approved", time, time, 2);
            var view = record.view();
            assertEquals(tool ? "tool" : "workflow", view.kind());
            assertSame(summary, view.summary());
            assertEquals(record.requestHash(), view.requestHash());
            assertEquals("2", view.revision());
        }
    }

    private ExecutionToolBinding binding(String name, String kind, String operation, Map<String, Object> config) {
        var definition = new ToolDefinition(name, "模型需要先读取文件，再按 old_text 和 replace_all 规则调用。", "schema", json.tree(Map.of("type", "object")), null,
            json.tree(Map.of("title", "操作名称")), operation, false, false, false, List.of(), 30);
        return new ExecutionToolBinding("resource", "version", kind, "资源", null, definition, json.tree(config));
    }
}
