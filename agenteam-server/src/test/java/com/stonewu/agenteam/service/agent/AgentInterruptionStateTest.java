package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.service.agent.AgentModelFactory.ConfiguredModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用当前依赖的真实框架，区分原生中断保存和项目历史恢复的行为。
 */
class AgentInterruptionStateTest {
    @TempDir
    Path directory;

    @Test
    void nativeInterruptIsObservedOnTheNextChunkAndPreservesEarlierText() throws Exception {
        String partial = "已经写出的接入说明，还没有输出完。";
        var model = mock(ConfiguredModel.class);
        when(model.getModelName()).thenReturn("中断验证模型");
        when(model.getContextWindowSize()).thenReturn(32768);
        var calls = new AtomicInteger();
        var nextInput = new AtomicReference<List<Msg>>();
        var output = Sinks.many().unicast().<ChatResponse>onBackpressureBuffer();
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                return output.asFlux();
            }
            nextInput.set(List.copyOf(call.getArgument(0)));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("接着上次说明继续。").build()))
                .finishReason("stop").build());
        });
        var store = new JsonFileAgentStateStore(directory.resolve("state"));
        var context = RuntimeContext.builder().userId("user").sessionId("conversation").build();
        var received = new CountDownLatch(1);
        try (var agent = agent(model, store)) {
            var response = agent.streamEvents(List.of(new UserMessage("请说明接入步骤")), context).doOnNext(event -> {
                if (event instanceof TextBlockDeltaEvent text && text.getDelta().contains(partial)) {
                    received.countDown();
                }
            }).collectList().toFuture();
            try {
                assertEquals(Sinks.EmitResult.OK, output.tryEmitNext(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(partial).build())).build()));
                assertTrue(received.await(5, TimeUnit.SECONDS), "框架需要先输出真实文字，再触发用户中断");
                agent.interrupt(context);
                assertFalse(response.isDone(), "中断标记不会主动结束没有新片段的模型流");
                output.tryEmitNext(ChatResponse.builder().content(List.of(TextBlock.builder().text("中断后到达的片段").build())).build());
                response.get(5, TimeUnit.SECONDS);
            } finally {
                response.cancel(true);
            }
        }
        var saved = store.get("user", "conversation", "agent_state", AgentState.class).orElseThrow();
        assertTrue(saved.getContext().stream().anyMatch(message -> message.getTextContent().contains(partial)),
            "AgentScope 原生用户中断应把已经输出的文字保存到状态存储");
        assertFalse(saved.getContext().stream().anyMatch(message -> message.getTextContent().contains("中断后到达的片段")),
            "观察到中断标记后不应再接受新的输出");
        try (var nextAgent = agent(model, store)) {
            nextAgent.call(List.of(new UserMessage("你刚才写了什么")), context).block(Duration.ofSeconds(5));
        }
        assertTrue(nextInput.get().stream().anyMatch(message -> message.getTextContent().contains(partial)),
            "新实例的下一次模型请求应读取已保存的部分回复");
    }

    private HarnessAgent agent(ConfiguredModel model, JsonFileAgentStateStore store) {
        return HarnessAgent.builder().name("中断验证").model(model).workspace(directory).sysPrompt("继续完成用户任务。")
            .stateStore(store).disableFilesystemTools().disableShellTool().disableDynamicSkills().disableDefaultWorkspaceSkills()
            .disableSubagents().disableMemoryTools().disableMemoryHooks().disableToolsConfig()
            .disableWorkspaceContext().disableAtPathExpansion().disableSessionPersistence().disableToolResultEviction()
            .enableAgentTracingLog(false).build();
    }
}
