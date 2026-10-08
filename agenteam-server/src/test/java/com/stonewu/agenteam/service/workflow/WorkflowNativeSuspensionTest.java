package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import com.stonewu.agenteam.service.agent.EncryptedAgentStateStore;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.State;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证工作流接入所依赖的真实框架暂停协议和加密状态，不向模型伪造确认或完成结果。
 */
class WorkflowNativeSuspensionTest {
    @TempDir
    Path workspace;
    private final AtomicInteger localToolCalls = new AtomicInteger();

    @Test
    void externalToolsWaitForAllMatchingResultsAcrossAgentInstances() throws Exception {
        var modelCalls = new AtomicInteger();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("工作流暂停验收模型");
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            if (modelCalls.getAndIncrement() == 0) {
                return Flux.just(ChatResponse.builder().content(List.of(
                        ToolUseBlock.builder().id("flow-a").name("workflow_probe").content("{\"value\":1}").build(),
                        ToolUseBlock.builder().id("flow-b").name("workflow_probe").content("{\"value\":2}").build()))
                    .finishReason("tool_calls").build());
            }
            List<Msg> input = call.getArgument(0);
            var results = input.stream().flatMap(value -> value.getContent().stream())
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
            assertEquals(List.of("flow-a", "flow-b"), results.stream().map(ToolResultBlock::getId).sorted().toList());
            assertTrue(results.stream().allMatch(value -> value.getState() == ToolResultState.SUCCESS));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("两个流程都已返回真实结果").build()))
                .finishReason("stop").build());
        });
        var first = invoke(model, List.of(new UserMessage("启动两个流程")));
        var waiting = first.stream().filter(RequireExternalExecutionEvent.class::isInstance)
            .map(RequireExternalExecutionEvent.class::cast).findFirst().orElseThrow();
        assertEquals(2, waiting.getToolCalls().size());
        assertEquals(1, modelCalls.get());
        assertEquals(0, localToolCalls.get());

        assertThrows(RuntimeException.class, () -> invoke(model, List.of(new ToolResultMessage(result("unrelated", "错误编号")))));
        assertEquals(1, modelCalls.get(), "不匹配的结果不能触发后续模型请求");
        var partial = invoke(model, List.of(new ToolResultMessage(result("flow-a", "结果甲"))));
        assertEquals(1, modelCalls.get(), "另一项流程未完成时必须继续等待");
        var remaining = partial.stream().filter(RequireExternalExecutionEvent.class::isInstance)
            .map(RequireExternalExecutionEvent.class::cast).findFirst().orElseThrow();
        assertEquals(List.of("flow-b"), remaining.getToolCalls().stream().map(ToolUseBlock::getId).toList());
        var partialResult = partial.stream().filter(ExternalExecutionResultEvent.class::isInstance)
            .map(ExternalExecutionResultEvent.class::cast).findFirst().orElseThrow();
        assertEquals(waiting.getReplyId(), partialResult.getReplyId());

        var finished = invoke(model, List.of(new ToolResultMessage(result("flow-b", "结果乙"))));
        assertEquals(2, modelCalls.get());
        assertEquals(0, localToolCalls.get());
        assertFalse(finished.stream().anyMatch(RequireExternalExecutionEvent.class::isInstance));
        assertTrue(finished.stream().filter(AgentResultEvent.class::isInstance).map(AgentResultEvent.class::cast)
            .anyMatch(value -> value.getResult().getTextContent().equals("两个流程都已返回真实结果")));
        var completedResult = finished.stream().filter(ExternalExecutionResultEvent.class::isInstance)
            .map(ExternalExecutionResultEvent.class::cast).findFirst().orElseThrow();
        assertEquals(remaining.getReplyId(), completedResult.getReplyId());
    }

    @Test
    void applicationStateIsEncryptedVersionedAndCopiedWithoutAnAgentState() throws Exception {
        var source = store("source", "第一次领取");
        var state = new ProbeState("approval", "{\"number\":9007199254740993,\"secret\":\"仅保存在加密文件\"}");
        source.save("user", "session", "workflow_state", state);
        assertTrue(source.exists("user", "session"));
        assertEquals(1, source.getVersioned("user", "session", "workflow_state", ProbeState.class).version());
        var target = store("copy", "第二次领取");
        source.copyTo(target);
        assertEquals(state, store("copy", "第二次领取").get("user", "session", "workflow_state", ProbeState.class).orElseThrow());
        assertTrue(target.matches(target.manifest()));
        for (String file : target.manifest().keySet()) {
            var content = Files.readString(workspace.resolve("copy").resolve(file));
            assertFalse(content.contains("仅保存在加密文件"));
            assertFalse(content.contains("9007199254740993"));
        }
        assertThrows(IllegalStateException.class,
            () -> store("copy", "错误领取").get("user", "session", "workflow_state", ProbeState.class));
    }

    private List<AgentEvent> invoke(Model model, List<Msg> input) throws Exception {
        var agent = HarnessAgent.builder().name("workflow-probe").model(model).workspace(workspace.resolve("agent"))
            .stateStore(store("agent-state", "验收执行")).maxIters(4)
            .disableFilesystemTools().disableShellTool().disableDynamicSkills().disableDefaultWorkspaceSkills()
            .disableDynamicSubagents().disableSubagents().disableMemoryTools().disableMemoryHooks().disableCompaction()
            .disableToolsConfig().disableWorkspaceContext().disableAtPathExpansion().disableSessionPersistence()
            .disableToolResultEviction().enableAgentTracingLog(false).build();
        agent.getToolkit().registerAgentTool(new ExternalProbe());
        try {
            var events = agent.streamEvents(input, RuntimeContext.builder().userId("user").sessionId("session").build())
                .collectList().block(Duration.ofSeconds(10));
            assertNotNull(events);
            return events;
        } finally {
            agent.close();
        }
    }

    private EncryptedAgentStateStore store(String directory, String binding) throws Exception {
        var json = new ObjectMapper();
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var keys = new ApplicationSecretKeys(key, json.writeValueAsString(Map.of("1", key)), "1", workspace.resolve("keys.properties").toString());
        return new EncryptedAgentStateStore(workspace.resolve(directory), binding, new PayloadEncryption(keys, json), json);
    }

    private ToolResultBlock result(String id, String value) {
        return ToolResultBlock.builder().id(id).name("workflow_probe").state(ToolResultState.SUCCESS)
            .output(TextBlock.builder().text(value).build()).build();
    }

    public record ProbeState(String next, String output) implements State {
    }

    private final class ExternalProbe extends ToolBase {
        ExternalProbe() {
            super(ToolBase.builder().name("workflow_probe").description("启动真实工作流并等待结果")
                .inputSchema(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "integer")),
                    "required", List.of("value"), "additionalProperties", false))
                .externalTool(true).readOnly(false).concurrencySafe(true));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
            return Mono.just(PermissionDecision.allow("流程入口已授权；流程内的实际写入仍需单独确认。"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            localToolCalls.incrementAndGet();
            return Mono.error(new IllegalStateException("外部工具应由框架暂停，不应调用本地实现"));
        }
    }
}
