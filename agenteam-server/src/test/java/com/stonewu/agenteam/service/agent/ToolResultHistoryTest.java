package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolResultContent;
import com.stonewu.agenteam.service.file.Utf8Text;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ToolResultHistoryTest {
    @Test
    void shortensSavedOldResultsWithoutLosingLatestBatchOrToolPairing() {
        var mapper = new ObjectMapper();
        var json = new ResourceJson(mapper);
        var value = mapper.createObjectNode().put("content", "完整结果🙂".repeat(2500));
        value.set(ToolResultContent.REFERENCE, ToolResultContent.reference("saved", "saved", value, 40000, 2048));
        String full = value.toString();
        var user = Msg.builder().role(MsgRole.USER).textContent("继续分析原来的文件").build();
        var old = result("old", full);
        var current = result("latest", full);
        var use = Msg.builder().role(MsgRole.ASSISTANT).content(ToolUseBlock.builder().id("latest").name("platform_read_url_version").input(Map.of()).build()).build();
        var messages = List.of(user, old, use, current);
        var shortened = ToolResultHistory.shorten(messages, ToolResultHistory.latestCalls(messages), json, 2048);
        assertSame(user, shortened.get(0));
        assertSame(use, shortened.get(2));
        assertSame(current, shortened.get(3));
        var before = (ToolResultBlock) old.getContent().getFirst();
        var after = (ToolResultBlock) shortened.get(1).getContent().getFirst();
        assertEquals(before.getId(), after.getId());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getState(), after.getState());
        String reference = ((TextBlock) after.getOutput().getFirst()).getText();
        assertTrue(Utf8Text.size(reference) <= 2048);
        assertEquals("tool-results/saved/content.txt", json.read(reference).path("path").asText());
        assertEquals(full, ((TextBlock) before.getOutput().getFirst()).getText());
        // 即使模型上下文很大，已保存的旧结果也立即改成可读取的引用，不继续积累全文副本。
        var run = mock(RunRecord.class);
        when(run.executionConfig()).thenReturn(mapper.createObjectNode());
        var execution = mock(ToolExecutionService.class);
        when(execution.resultSettings()).thenReturn(new ToolResultSettings(32, 64, 2, 8, 200, 2, 20));
        var model = mock(Model.class);
        when(model.getContextWindowSize()).thenReturn(1_000_000);
        var input = new ModelCallInput(messages, List.of(), GenerateOptions.builder().build(), model);
        var context = RuntimeContext.builder().userId("user").sessionId("session").build();
        try (var runtime = new ExecutionToolRuntime(run, null, Map.of(), null, execution, null, json, Duration.ZERO)) {
            var bounded = runtime.shortenHistory(mock(Agent.class), context, input);
            String oldResult = ((TextBlock) ((ToolResultBlock) bounded.messages().get(1).getContent().getFirst())
                .getOutput().getFirst()).getText();
            assertTrue(Utf8Text.size(oldResult) <= 2048);
            assertSame(current, bounded.messages().get(3));
            assertEquals(full, ((TextBlock) before.getOutput().getFirst()).getText());
        }
        var nextQuestion = new ArrayList<>(messages);
        nextQuestion.add(Msg.builder().role(MsgRole.ASSISTANT).textContent("本轮已完成").build());
        nextQuestion.add(Msg.builder().role(MsgRole.USER).textContent("继续下一轮").build());
        assertTrue(ToolResultHistory.latestCalls(nextQuestion).isEmpty(), "新一轮提问不能让上轮工具全文继续占用最近结果名额");
        var nextHistory = ToolResultHistory.shorten(nextQuestion, Set.of(), json, 2048);
        var oldCurrent = (ToolResultBlock) nextHistory.get(3).getContent().getFirst();
        assertTrue(Utf8Text.size(((TextBlock) oldCurrent.getOutput().getFirst()).getText()) <= 2048);
    }

    private Msg result(String id, String content) {
        return Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.builder().id(id).name("platform_read_url_version").output(TextBlock.builder().text(content).build()).build()).build();
    }
}
