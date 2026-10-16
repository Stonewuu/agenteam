package com.stonewu.agenteam.service.workflow;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowNodeSlotsTest {
    @Test
    void sharesFourSlotsWithoutBlockingTheSingleExecutionThread() {
        var slots = new WorkflowNodeSlots(4);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var scheduler = Schedulers.newSingle("workflow-slot-test");
        try {
            var results = Flux.range(0, 24).flatMap(index -> slots.execute(Mono.defer(() -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                return Mono.delay(Duration.ofMillis(5), scheduler).map(ignored -> index)
                    .doOnTerminate(active::decrementAndGet);
            })).subscribeOn(scheduler), 24).collectList().block(Duration.ofSeconds(3));
            assertNotNull(results);
            assertEquals(24, results.size());
            assertTrue(maximum.get() <= 4);
            assertTrue(maximum.get() > 1);
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void cancellationWhileRunningOrWaitingCannotLeakOrDuplicateSlots() {
        var slots = new WorkflowNodeSlots(1);
        var started = Sinks.<Boolean>one();
        var first = slots.execute(Mono.defer(() -> {
            started.tryEmitValue(true);
            return Mono.never();
        })).subscribe();
        assertEquals(true, started.asMono().block(Duration.ofSeconds(1)));
        var waiting = slots.execute(Mono.just("不能提前运行")).subscribe();
        waiting.dispose();
        first.dispose();
        assertEquals("名额已释放", slots.execute(Mono.just("名额已释放")).block(Duration.ofSeconds(1)));
        assertEquals("超时", slots.execute(Mono.never()).timeout(Duration.ofMillis(20))
            .onErrorReturn("超时").block(Duration.ofSeconds(1)));
        assertEquals("仍可执行", slots.execute(Mono.just("仍可执行")).block(Duration.ofSeconds(1)));
    }
}
