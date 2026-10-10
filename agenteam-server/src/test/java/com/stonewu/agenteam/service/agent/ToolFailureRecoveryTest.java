package com.stonewu.agenteam.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.tool.ToolResultSettings;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolCallTransactions;
import com.stonewu.agenteam.service.tool.ToolExecutionService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolFailureRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void unknownPreparationAndInvocationFailuresDoNotLoseOtherResultsOrStopTheLoop() {
        var json = new ResourceJson(new ObjectMapper());
        var run = mock(RunRecord.class);
        when(run.id()).thenReturn("run");
        when(run.conversationId()).thenReturn("session");
        when(run.executionConfig()).thenReturn(json.tree(Map.of()));
        var lease = mock(JobLease.class);
        when(lease.runId()).thenReturn("run");
        var transaction = mock(ToolCallTransactions.class);
        var record = mock(ToolCallRecord.class);
        when(record.status()).thenReturn("prepared");
        when(transaction.prepare(any(), any(), any(), any(), any())).thenAnswer(call -> {
            ToolUseBlock use = call.getArgument(4);
            if (use.getId().equals("prepare-error")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENTS_INVALID", "工具参数过大，请缩小后继续。");
            }
            return record;
        });
        var execution = mock(ToolExecutionService.class);
        when(execution.resultSettings()).thenReturn(new ToolResultSettings(32, 64, 2, 8, 200, 2, 20));
        when(execution.invoke(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any())).thenAnswer(call -> {
            ToolUseBlock use = call.getArgument(4);
            if (use.getId().equals("invoke-error")) {
                throw new IllegalStateException("模拟工具异常");
            }
            if (use.getId().equals("checked-error")) {
                throw new IOException("模拟异步工具读取失败");
            }
            return json.tree(Map.of("content", "工具已完成"));
        });
        var definition = new ToolDefinition("read_test", "读取测试内容", "schema",
            json.tree(Map.of("type", "object", "properties", Map.of())), null, json.tree(Map.of()),
            "read", false, false, true, List.of(), 30);
        var binding = new ExecutionToolBinding("resource", "version", "plugin", "测试工具", null,
            definition, json.tree(Map.of()));
        var runtime = new ExecutionToolRuntime(run, lease, Map.of(binding.alias(), binding), transaction, execution,
            mock(ToolCallMapper.class), json, Duration.ZERO);
        var guard = new ExecutionGuardMiddleware(mock(RunLifecycleService.class), lease, "session", 0);
        guard.tools(runtime);
        var turn = new AtomicInteger();
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("tool-recovery-model");
        when(model.getContextWindowSize()).thenReturn(100000);
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            List<Msg> messages = call.getArgument(0);
            int step = turn.getAndIncrement();
            if (step == 0) {
                return Flux.just(ChatResponse.builder().content(List.of(
                    use("unknown", "missing_tool"), use("prepare-error", binding.alias()),
                    use("invoke-error", binding.alias()), use("checked-error", binding.alias()),
                    use("success", binding.alias())))
                    .finishReason("tool_calls").build());
            }
            var results = messages.stream().flatMap(message -> message.getContent().stream())
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
            assertEquals(step == 1 ? 5 : 6, results.size());
            assertEquals(4, results.stream().filter(result -> result.getState() == ToolResultState.ERROR).count());
            if (step == 1) {
                return Flux.just(ChatResponse.builder().content(List.of(use("corrected", binding.alias())))
                    .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("已完成最终答复").build()))
                .finishReason("stop").build());
        });
        try (var agent = HarnessAgent.builder().model(model).workspace(directory).stateStore(new InMemoryAgentStateStore()).middleware(guard)
            .generateOptions(GenerateOptions.builder().maxTokens(100).build()).maxIters(10)
            .disableSubagents().disableFilesystemTools().disableShellTool().disableDynamicSkills()
            .disableDefaultWorkspaceSkills().disableMemoryTools().disableMemoryHooks().disableToolsConfig()
            .disableWorkspaceContext().disableAtPathExpansion().disableSessionPersistence().disableToolResultEviction()
            .enableAgentTracingLog(false).build()) {
            AgentToolkitPolicy.retain(agent, Set.of());
            runtime.attach(agent, false);
            var context = RuntimeContext.builder().userId("user").sessionId("session").build();
            var events = agent.streamEvents(new UserMessage("开始任务"), context).collectList().block(Duration.ofSeconds(15));
            assertEquals(3, turn.get());
            assertEquals(6, events.stream().filter(ToolResultEndEvent.class::isInstance).count());
            assertTrue(events.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
                .anyMatch(event -> event.getResult().getTextContent().equals("已完成最终答复")));
            runtime.requireActive();
            runtime.requireSuccessful();
            verify(execution, times(4)).invoke(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        } finally {
            runtime.close();
        }
    }

    private static ToolUseBlock use(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).build();
    }

    @Test
    void explicitStopAndRunLimitsRemainTerminalButToolLimitsDoNot() {
        assertTrue(ToolFailureRecovery.terminal(new RunLifecycleService.ExecutionStoppedException()));
        assertTrue(ToolFailureRecovery.terminal(new ApiException(HttpStatus.CONFLICT, "EXECUTION_STEP_LIMIT", "步骤已用完")));
        assertFalse(ToolFailureRecovery.terminal(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "REMOTE_TIMEOUT", "工具超时")));
        assertFalse(ToolFailureRecovery.terminal(new IllegalArgumentException("参数不正确")));
    }
}
