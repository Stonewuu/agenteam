package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentStreamEventMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用实际框架验证升级修复；不以发布说明或版本号代替对话行为证据。
 */
class AgentScope203RegressionTest {
    @TempDir
    Path workspace;

    @Test
    void backpressuredHttpStreamDeliversTextBeforeServerFinishes() throws Exception {
        var firstReceived = new CountDownLatch(1);
        var allowFinish = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var transport = new OkHttpTransport(HttpTransportConfig.defaults());
        try (var executor = Executors.newSingleThreadExecutor()) {
            server.setExecutor(executor);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    var output = exchange.getResponseBody();
                    output.write("data: {\"id\":\"response-1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"第一段\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    if (!allowFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("客户端未及时读取第一段内容");
                    }
                    output.write("data: {\"id\":\"response-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"第二段\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var model = OpenAIChatModel.builder().modelName("isolated-stream-model").apiKey("isolated-test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").httpTransport(transport).stream(true).build();
            var result = model.stream(List.of(new UserMessage("读取流式内容")), List.of(), null).limitRate(1)
                .doOnNext(value -> {
                    if (!value.getContent().isEmpty()) {
                        firstReceived.countDown();
                    }
                }).collectList().toFuture();
            try {
                assertTrue(firstReceived.await(3, TimeUnit.SECONDS), "不能等服务端结束才一次性收到全部片段");
                assertFalse(result.isDone());
            } finally {
                allowFinish.countDown();
            }
            var replies = result.get(5, TimeUnit.SECONDS);
            String text = replies.stream().flatMap(value -> value.getContent().stream()).filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast).map(TextBlock::getText).reduce("", String::concat);
            assertEquals("第一段第二段", text);
        } finally {
            allowFinish.countDown();
            server.stop(0);
            transport.close();
        }
    }

    @Test
    void stateLoadFailureDoesNotReplaceTheStoredConversationWithEmptyContext() {
        var store = new InMemoryAgentStateStore() {
            @Override
            public <T extends State> VersionedState<T> getVersioned(String user, String session, String key, Class<T> type) {
                if (session.equals("unreadable-session")) {
                    throw new IllegalStateException("测试状态读取失败");
                }
                return super.getVersioned(user, session, key, type);
            }
        };
        var agent = ReActAgent.builder().name("state-read-test").model(mock(Model.class)).stateStore(store).build();
        assertThrows(IllegalStateException.class, () -> agent.getAgentState("isolated-user", "unreadable-session"));
        assertTrue(store.get("isolated-user", "unreadable-session", "agent_state", AgentState.class).isEmpty());
    }

    @Test
    void parallelChildLifecyclesHaveStableDistinctReplyIdentifiers() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("isolated-subagent-model");
        AtomicInteger parentCalls = new AtomicInteger();
        when(model.stream(any(), any(), any())).thenAnswer(call -> {
            List<Msg> input = call.getArgument(0);
            String question = input.stream().filter(value -> value.getRole() == MsgRole.USER).map(Msg::getTextContent).reduce((a, b) -> b).orElse("");
            if (question.equals("父请求") && parentCalls.getAndIncrement() == 0) {
                return Flux.just(ChatResponse.builder().id("parent-first").content(List.of(
                        ToolUseBlock.builder().id("spawn-a").name("agent_spawn").content("{\"agent_id\":\"researcher\",\"label\":\"甲\",\"task\":\"子问题甲\"}").build(),
                        ToolUseBlock.builder().id("spawn-b").name("agent_spawn").content("{\"agent_id\":\"researcher\",\"label\":\"乙\",\"task\":\"子问题乙\"}").build()))
                    .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(question.equals("父请求") ? "父结果" : question + "结果").build()))
                .finishReason("stop").build());
        });
        var agent = HarnessAgent.builder().name("parent").model(model).workspace(workspace)
            .stateStore(new InMemoryAgentStateStore()).maxIters(4)
            .subagent(SubagentDeclaration.builder().name("researcher").description("处理父任务选定的问题")
                .workspaceMode(WorkspaceMode.ISOLATED).inlineAgentsBody("只处理交给你的问题，不再创建子智能体。")
                .steps(2).persistSession(true).build())
            .disableFilesystemTools().disableShellTool().disableDynamicSkills().disableDefaultWorkspaceSkills()
            .disableMemoryTools().disableMemoryHooks().disableCompaction().disableToolsConfig().disableWorkspaceContext().disableAtPathExpansion().build();
        try {
            var events = agent.streamEvents(new UserMessage("父请求"), RuntimeContext.builder().userId("isolated-user").sessionId("parent-session").build())
                .collectList().block(Duration.ofSeconds(10));
            assertNotNull(events);
            var starts = events.stream().filter(AgentStartEvent.class::isInstance).map(AgentStartEvent.class::cast)
                .filter(value -> value.getSource() != null).toList();
            var ends = events.stream().filter(AgentEndEvent.class::isInstance).map(AgentEndEvent.class::cast)
                .filter(value -> value.getSource() != null).toList();
            assertEquals(2, starts.size(), () -> events.stream().filter(event -> event.getType().name().startsWith("TOOL_RESULT"))
                .map(event -> new AgentStreamEventMapper().map(event).details().toString()).toList().toString());
            assertEquals(2, ends.size());
            assertEquals(2, starts.stream().map(AgentStartEvent::getReplyId).distinct().count());
            for (var start : starts) {
                assertNotNull(start.getReplyId());
                assertTrue(ends.stream().anyMatch(end -> start.getReplyId().equals(end.getReplyId())));
                assertEquals(start.getReplyId(), new AgentStreamEventMapper().map(start).replyId());
            }
        } finally {
            agent.close();
        }
    }
}
