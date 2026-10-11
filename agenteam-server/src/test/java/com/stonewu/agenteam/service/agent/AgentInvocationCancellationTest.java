package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentStreamEventMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import io.agentscope.core.agent.AgentRun;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentInvocationCancellationTest {
    @Test
    void cancellingOneInvocationDoesNotCancelAnotherHandleOnTheSameAgent() throws Exception {
        var harness = mock(HarnessAgent.class);
        var started = new CountDownLatch(2);
        var firstEvents = Sinks.many().unicast().<AgentEvent>onBackpressureBuffer();
        var secondEvents = Sinks.many().unicast().<AgentEvent>onBackpressureBuffer();
        var firstRun = AgentRun.create("agent", () -> firstEvents.asFlux().doOnSubscribe(ignored -> started.countDown()));
        var secondRun = AgentRun.create("agent", () -> secondEvents.asFlux().doOnSubscribe(ignored -> started.countDown()));
        when(harness.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(firstRun, secondRun);
        try (var first = task(harness); var second = task(harness)) {
            var firstDone = first.completion().toFuture();
            var secondDone = second.completion().toFuture();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            first.cancel();
            firstDone.get(3, TimeUnit.SECONDS);
            assertTrue(first.cancelled());
            assertEquals(AgentRun.Status.CANCELLED, firstRun.status());
            assertFalse(secondDone.isDone());
            assertFalse(secondRun.status().isTerminal());
            secondEvents.tryEmitComplete();
            secondDone.get(3, TimeUnit.SECONDS);
            assertEquals(AgentRun.Status.COMPLETED, secondRun.status());
            assertFalse(second.cancelled());
        }
    }

    @Test
    void cancellationBeforeSubscriptionNeverStartsTheModel() {
        var harness = mock(HarnessAgent.class);
        var starts = new AtomicInteger();
        AgentRun<AgentEvent> invocation = AgentRun.create("agent", () -> {
            starts.incrementAndGet();
            return Flux.never();
        });
        when(harness.prepareRun(anyList(), any(RuntimeContext.class))).thenReturn(invocation);
        try (var task = task(harness)) {
            task.cancel();
            task.completion().block(Duration.ofSeconds(2));
            assertEquals(0, starts.get());
            assertEquals(AgentRun.Status.CANCELLED, invocation.status());
        }
    }

    private AgentExecutionAdapter.Task task(HarnessAgent harness) {
        var persistence = mock(FrameEventPersistence.class);
        when(persistence.stream()).thenReturn(mock(FrameEventPersistence.Stream.class));
        when(persistence.failure()).thenReturn(Sinks.empty());
        var context = RuntimeContext.builder().userId("user").sessionId("session").build();
        var execution = new AgentExecutionFactory.Execution(harness, context, null,
            mock(ExecutionGuardMiddleware.class), List.of(), mock(ExecutionToolRuntime.class), false, Duration.ZERO);
        var adapter = new AgentExecutionAdapter(null, new AgentStreamEventMapper(), null, null, null, null, null);
        return adapter.createScoped(mock(RunRecord.class), mock(JobLease.class), execution, persistence,
            List.of(new UserMessage("测试请求")));
    }
}
