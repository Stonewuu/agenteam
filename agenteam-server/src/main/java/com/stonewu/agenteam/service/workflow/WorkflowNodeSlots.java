package com.stonewu.agenteam.service.workflow;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** 循环与并行分支共享名额，等待时不占用执行线程，取消和超时也会释放名额。 */
public final class WorkflowNodeSlots {
    private final Semaphore available;

    public WorkflowNodeSlots(int maximum) {
        available = new Semaphore(maximum);
    }

    public <T> Mono<T> execute(Mono<T> work) {
        return Mono.usingWhen(acquire(), ignored -> work, slot -> Mono.fromRunnable(slot::close));
    }

    private Mono<Slot> acquire() {
        return Mono.defer(() -> available.tryAcquire() ? Mono.just(new Slot(available))
            : Mono.delay(Duration.ofMillis(10)).then(acquire())).doOnDiscard(Slot.class, Slot::close);
    }

    private static final class Slot {
        private final Semaphore available;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Slot(Semaphore available) {
            this.available = available;
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                available.release();
            }
        }
    }
}
