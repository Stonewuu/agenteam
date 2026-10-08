package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import com.stonewu.agenteam.service.http.ApiException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 用真实框架验证先压缩再请求模型，以及摘要失败时不丢失原上下文。
 */
class AgentContextCompactionTest {
    @TempDir
    Path directory;

    @Test
    void thresholdReservesOutputAndFivePercentOfInputSpace() {
        assertEquals(85500, AgentContextCompaction.threshold(model(100000, 10000)));
        assertEquals(1992324464, AgentContextCompaction.threshold(model(Integer.MAX_VALUE, 50300000)));
        assertThrows(ApiException.class, () -> AgentContextCompaction.threshold(model(10000, 10000)));
    }

    @Test
    void longHistoryIsSummarizedBeforeReasoningAndSavedInAgentState() {
        var model = model(10000, 1000);
        var summaries = new AtomicInteger();
        var normal = new AtomicInteger();
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            List<Msg> messages = call.getArgument(0);
            GenerateOptions options = call.getArgument(2);
            assertEquals(1000, options.getMaxTokens());
            if (messages.size() == 1) {
                summaries.incrementAndGet();
                assertEquals(List.of(), call.getArgument(1));
                return reply("已完成前期分析，保留用户要求，下一步继续验证。");
            }
            normal.incrementAndGet();
            assertTrue(messages.stream().anyMatch(message -> ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName())));
            assertTrue(messages.size() < 20);
            return reply("继续完成任务。");
        });
        try (var agent = agent(model)) {
            var context = RuntimeContext.builder().userId("user").sessionId("conversation").build();
            agent.call(history(), context).block(Duration.ofSeconds(10));
            assertEquals(1, summaries.get());
            assertEquals(1, normal.get());
            var saved = agent.getDelegate().getAgentState("user", "conversation").contextMutable();
            assertTrue(saved.stream().anyMatch(message -> ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName())));
        }
    }

    @Test
    void summaryFailureDoesNotReplaceHistoryWithAnErrorMessage() {
        var model = model(10000, 1000);
        when(model.stream(any(), any(), any())).thenReturn(Flux.error(new IllegalStateException("测试摘要服务暂不可用")));
        try (var agent = agent(model)) {
            var context = RuntimeContext.builder().userId("user").sessionId("conversation").build();
            assertThrows(ApiException.class, () -> agent.call(history(), context).block(Duration.ofSeconds(10)));
            var saved = agent.getDelegate().getAgentState("user", "conversation").contextMutable();
            assertTrue(saved.stream().anyMatch(message -> message.getTextContent().contains("原始任务第0段")));
            assertFalse(saved.stream().anyMatch(message -> ConversationCompactor.SUMMARY_MSG_NAME.equals(message.getName())));
            verify(model, times(1)).stream(any(), any(), any());
        }
    }

    @Test
    void contextCheckUsesTheSameEstimateAndReservesReasoningOutput() {
        var model = model(10000, 1000);
        var options = GenerateOptions.builder().maxCompletionTokens(1000).build();
        var messages = List.of(Msg.builder().role(MsgRole.USER).textContent("中文内容".repeat(1500)).build());
        assertDoesNotThrow(() -> AgentContextBudget.requireFits(new ModelCallInput(messages, List.of(), options, model)));
        var insufficient = GenerateOptions.builder().maxCompletionTokens(9000).build();
        assertThrows(ApiException.class, () -> AgentContextBudget.requireFits(new ModelCallInput(messages, List.of(), insufficient, model)));
    }

    @Test
    void modelOverflowDoesNotEnterTheFrameworkFallbackThatWritesPlaintextHistory() {
        var model = model(10000, 1000);
        when(model.stream(any(), any(), any())).thenReturn(Flux.error(new IllegalStateException("maximum context exceeded")));
        try (var agent = agent(model)) {
            var context = RuntimeContext.builder().userId("user").sessionId("conversation").build();
            var input = List.of(Msg.builder().role(MsgRole.USER).textContent("单条输入无法通过删除历史解决").build());
            var failure = assertThrows(ApiException.class, () -> agent.call(input, context).block(Duration.ofSeconds(10)));
            assertEquals("EXECUTION_CONTEXT_LIMIT", failure.code());
            verify(model, times(1)).stream(any(), any(), any());
        }
    }

    @Test
    void summarizationChecksExecutionPermissionBeforeCallingTheModel() {
        var model = model(10000, 1000);
        var guard = mock(ExecutionGuardMiddleware.class);
        when(guard.beforeCompaction(any())).thenReturn(Mono.error(new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_REVOKED", "测试任务权限已撤销")));
        try (var agent = agent(model, guard)) {
            var context = RuntimeContext.builder().userId("user").sessionId("conversation").build();
            var failure = assertThrows(ApiException.class, () -> agent.call(history(), context).block(Duration.ofSeconds(10)));
            assertEquals("PERMISSION_REVOKED", failure.code());
            verify(model, never()).stream(any(), any(), any());
            assertTrue(agent.getDelegate().getAgentState("user", "conversation").contextMutable().stream()
                .anyMatch(message -> message.getTextContent().contains("原始任务第0段")));
        }
    }

    private HarnessAgent agent(ConfiguredModel model) {
        return agent(model, null);
    }

    private HarnessAgent agent(ConfiguredModel model, ExecutionGuardMiddleware guard) {
        var options = GenerateOptions.builder().maxTokens(model.maxOutputTokens()).build();
        return HarnessAgent.builder().name("压缩验证").model(model).workspace(directory).sysPrompt("继续完成用户任务。")
            .stateStore(new InMemoryAgentStateStore())
            .compaction(AgentContextCompaction.create(model, options, "test-run", guard))
            .middleware(new CompactionSafetyMiddleware()).generateOptions(options)
            .disableFilesystemTools().disableShellTool().disableDynamicSkills().disableDefaultWorkspaceSkills()
            .disableSubagents().disableMemoryTools().disableMemoryHooks().disableToolsConfig()
            .disableWorkspaceContext().disableAtPathExpansion().disableSessionPersistence().disableToolResultEviction()
            .enableAgentTracingLog(false).build();
    }

    private ConfiguredModel model(int context, int output) {
        var model = mock(ConfiguredModel.class);
        when(model.getModelName()).thenReturn("压缩测试模型");
        when(model.getContextWindowSize()).thenReturn(context);
        when(model.maxOutputTokens()).thenReturn(output);
        return model;
    }

    private List<Msg> history() {
        var messages = new ArrayList<Msg>();
        for (int i = 0; i < 36; i++) {
            messages.add(Msg.builder().role(i % 2 == 0 ? MsgRole.USER : MsgRole.ASSISTANT)
                .textContent("原始任务第" + i + "段：" + "保留约束和已经确认的执行结果。".repeat(45)).build());
        }
        messages.add(Msg.builder().role(MsgRole.USER).textContent("请继续完成剩余任务。").build());
        return messages;
    }

    private Flux<ChatResponse> reply(String text) {
        return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).finishReason("stop").build());
    }
}
