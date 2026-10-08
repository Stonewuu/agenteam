package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ContentBlock.ToolBlockDetails;
import com.stonewu.agenteam.model.execution.response.MessageView;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentHistoryMessageMapperTest {
    @Test
    void preservesInterleavedTextAndToolResultsWithoutDuplicatingBodyOrResumingCalls() {
        var before = text("before", 0, "先检查配置。");
        var tool = tool("read", 1, "completed", "配置入口为 application.yml");
        var after = text("after", 2, "接着补充尚未写完的说明。");
        var mapped = AgentHistoryMessageMapper.map(message("先检查配置。\n\n接着补充尚未写完的说明。", List.of(after, tool, before)), "");
        String content = mapped.getTextContent();
        assertTrue(content.indexOf("先检查配置。") < content.indexOf("配置入口为 application.yml"));
        assertTrue(content.indexOf("配置入口为 application.yml") < content.indexOf("接着补充尚未写完的说明。"));
        assertEquals(content.indexOf("先检查配置。"), content.lastIndexOf("先检查配置。"));
        assertTrue(content.contains("本轮回复已停止"));
        assertTrue(mapped.getContent().stream().allMatch(TextBlock.class::isInstance), "历史工具应保留为记录，不能变成待执行调用");
    }

    @Test
    void largeStoredResultsKeepAReadableReferenceInsteadOfOnlyTheShortUiPreview() {
        var json = new ObjectMapper();
        var record = mock(ToolCallRecord.class);
        when(record.id()).thenReturn("saved-call");
        when(record.status()).thenReturn("succeeded");
        when(record.resultRedacted()).thenReturn(json.createObjectNode().put("content", "已经确认的真实结果。".repeat(4000) + "末尾独有内容"));
        var block = tool("saved", 0, "completed", "页面中的短预览");
        String content = AgentHistoryMessageMapper.map(message("", List.of(block)), "", Map.of(block.stepId(), record)).getTextContent();
        assertTrue(content.contains("已经确认的真实结果"));
        assertTrue(content.contains("tool-results/saved-call/content.txt"), "模型需要能沿已有授权读取工具结果全文");
        assertFalse(content.contains("页面中的短预览"));
        assertFalse(content.contains("末尾独有内容"));
        assertTrue(content.length() < 5000, "不能把大结果全文重新塞进每轮历史");
    }

    @Test
    void unknownActualOutcomeOverridesAnEarlierCompletedDisplayState() {
        var record = mock(ToolCallRecord.class);
        when(record.status()).thenReturn("unknown");
        when(record.errorSummary()).thenReturn("操作已提交，但尚未确认最终结果。");
        var block = tool("uncertain", 0, "completed", "");
        String content = AgentHistoryMessageMapper.map(message("", List.of(block)), "", Map.of(block.stepId(), record)).getTextContent();
        assertTrue(content.contains("结果尚未确认"));
        assertTrue(content.contains("操作已提交，但尚未确认最终结果"));
        assertFalse(content.contains("状态：已完成"));
    }

    private MessageView message(String content, List<ContentBlock> blocks) {
        return new MessageView("output", "previous-run", 1, "assistant", content, "cancelled", blocks, List.of(), null,
            "2026-09-23T08:00:00Z", "2026-09-23T08:01:00Z");
    }

    private ContentBlock text(String id, int order, String content) {
        return new ContentBlock(id, "text", null, order, "1", content, "completed", null, null, null, null, null, null);
    }

    private ContentBlock tool(String id, int order, String state, String result) {
        return new ContentBlock(id, "tool", null, order, "1", "", state, "step-" + id, null, null, null, "文件 · 读取文件",
            new ToolBlockDetails(id, "read_file", null, "{\"path\":\"config/application.yml\"}", result, "completed", state));
    }
}
