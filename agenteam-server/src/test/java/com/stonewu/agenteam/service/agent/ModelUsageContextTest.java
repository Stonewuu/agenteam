package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.model.modelusage.entity.ModelCallContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ModelUsageContextTest {
    @Test
    void concurrentAgentsCanShareAModelWithoutSharingTheBillingOwner() {
        var seen = new CopyOnWriteArrayList<ModelCallContext>();
        var model = model(seen);
        var first = ReActAgent.builder().name("员工一").model(model).middleware(new ModelUsageContextMiddleware(owner("first"))).build();
        var second = ReActAgent.builder().name("员工二").model(model).middleware(new ModelUsageContextMiddleware(owner("second"))).build();
        var messages = List.<Msg>of(new UserMessage("测试请求"));
        Flux.merge(first.call(messages, RuntimeContext.builder().userId("user").sessionId("session-one").build()),
            second.call(messages, RuntimeContext.builder().userId("user").sessionId("session-two").build()))
            .collectList().block(Duration.ofSeconds(4));
        assertEquals(2, seen.size());
        assertTrue(seen.stream().anyMatch(value -> value.runId().equals("first") && value.agentSessionId().equals("session-one")));
        assertTrue(seen.stream().anyMatch(value -> value.runId().equals("second") && value.agentSessionId().equals("session-two")));
    }

    @Test
    void compactionPreservesTheOwnerButChangesThePurposeForOnlyThatRequest() {
        var seen = new CopyOnWriteArrayList<ModelCallContext>();
        var model = model(seen);
        var context = owner("run").forSession("session");
        var compaction = AgentContextCompaction.create(model, GenerateOptions.builder().build(), "run");
        compaction.getModel().stream(List.of(new UserMessage("需要压缩的历史")), List.of(), null)
            .contextWrite(values -> values.put(ModelCallContext.class, context)).collectList().block(Duration.ofSeconds(2));
        model.stream(List.of(), List.of(), null).contextWrite(values -> values.put(ModelCallContext.class, context))
            .collectList().block(Duration.ofSeconds(2));
        assertEquals("compaction", seen.get(0).purpose());
        assertEquals("agent", seen.get(1).purpose());
        assertEquals("run", seen.get(0).runId());
        assertEquals("session", seen.get(0).agentSessionId());
    }

    private ModelCallContext owner(String run) {
        return new ModelCallContext("enterprise", "user", "conversation", run, 1, 2,
            "employee", null, null, "conversation", "conversation", "agent");
    }

    private AgentModelFactory.ConfiguredModel model(List<ModelCallContext> seen) {
        return new AgentModelFactory.ConfiguredModel() {
            @Override
            public String getModelName() {
                return "shared";
            }

            @Override
            public int getContextWindowSize() {
                return 32000;
            }

            @Override
            public int maxOutputTokens() {
                return 1000;
            }

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.deferContextual(values -> {
                    seen.add(values.get(ModelCallContext.class));
                    return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("完成").build())).build())
                        .delayElements(Duration.ofMillis(5));
                });
            }
        };
    }
}
