package com.stonewu.agenteam.service.chat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatWorkRunnerTest {
    @Test
    void concurrentNotificationsAreCoalescedWithoutRunningOnTheCaller() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var again = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var thread = new AtomicReference<Thread>();
        try (var runner = new ChatWorkRunner("test-burst", () -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                thread.set(Thread.currentThread());
                if (calls.incrementAndGet() == 1) {
                    entered.countDown();
                    waitFor(release);
                } else {
                    again.countDown();
                }
                return false;
            } finally {
                active.decrementAndGet();
            }
        })) {
            runner.wake();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int index = 0; index < 1000; index++) {
                    threads.submit(runner::wake);
                }
            }
            assertEquals(1, calls.get());
            assertNotEquals(Thread.currentThread(), thread.get());
            release.countDown();
            assertTrue(again.await(5, TimeUnit.SECONDS));
            assertEquals(1, maximum.get());
            assertEquals(2, calls.get(), "正在运行时的连续通知只需再检查一次数据库");
        } finally {
            release.countDown();
        }
    }

    @Test
    void drainsFullBatchesAndRecoversAfterAFailure() throws Exception {
        var calls = new AtomicInteger();
        var drained = new CountDownLatch(1);
        var recovered = new CountDownLatch(1);
        try (var runner = new ChatWorkRunner("test-recovery", () -> {
            int current = calls.incrementAndGet();
            if (current < 4) {
                return true;
            }
            if (current == 4) {
                drained.countDown();
                throw new IllegalStateException("合成临时故障");
            }
            recovered.countDown();
            return false;
        })) {
            runner.wake();
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            runner.wake();
            assertTrue(recovered.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closedRunnerDoesNotStartNewWork() {
        var calls = new AtomicInteger();
        var runner = new ChatWorkRunner("test-closed", () -> {
            calls.incrementAndGet();
            return false;
        });
        runner.close();
        runner.wake();
        assertEquals(0, calls.get());
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被打断", failure);
        }
    }
}
