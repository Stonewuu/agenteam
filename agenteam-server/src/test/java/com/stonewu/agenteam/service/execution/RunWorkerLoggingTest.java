package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectSandboxUsageService;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RunWorkerLoggingTest {
    @Test
    void largeAndUnlimitedConcurrencyCanStartMoreThanThirtyTwoTasks() throws Exception {
        for (int limit : new int[]{1000, -1}) {
            var lifecycle = mock(RunLifecycleService.class);
            var tasks = mock(ExecutionTaskFactory.class);
            var leases = IntStream.range(0, 40).mapToObj(index -> {
                var lease = mock(JobLease.class);
                when(lease.id()).thenReturn("job-" + index);
                return lease;
            }).toList();
            var next = new AtomicInteger();
            when(lifecycle.claim(anyString())).thenAnswer(ignored -> {
                int index = next.getAndIncrement();
                return index < leases.size() ? Optional.of(leases.get(index)) : Optional.empty();
            });
            var run = mock(RunRecord.class);
            var task = mock(ExecutionTask.class);
            var started = new CountDownLatch(leases.size());
            when(lifecycle.start(any(JobLease.class))).thenReturn(Optional.of(run));
            when(tasks.create(eq(run), any(JobLease.class))).thenReturn(task);
            when(task.completion()).thenReturn(Mono.<Void>never().doOnSubscribe(ignored -> started.countDown()));
            try (var worker = new RunWorker(lifecycle, tasks, null, limit)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (started.getCount() != 0 && System.nanoTime() < deadline) {
                    worker.poll();
                    started.await(20, TimeUnit.MILLISECONDS);
                }
                assertTrue(started.getCount() == 0);
                verify(task, never()).cancel();
            }
        }
    }

    @Test
    void changingConcurrencyStartsMoreWorkAndDrainsWithoutCancellingActiveTasks() throws Exception {
        var lifecycle = mock(RunLifecycleService.class);
        var tasks = mock(ExecutionTaskFactory.class);
        var maximum = new AtomicInteger(1);
        var first = mock(JobLease.class);
        var second = mock(JobLease.class);
        var third = mock(JobLease.class);
        when(first.id()).thenReturn("first");
        when(second.id()).thenReturn("second");
        when(third.id()).thenReturn("third");
        var run = mock(RunRecord.class);
        when(lifecycle.claim(anyString())).thenReturn(Optional.of(first), Optional.of(second), Optional.of(third), Optional.empty());
        var firstTask = mock(ExecutionTask.class);
        var secondTask = mock(ExecutionTask.class);
        var thirdTask = mock(ExecutionTask.class);
        var firstDone = Sinks.<Void>empty();
        var secondDone = Sinks.<Void>empty();
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var thirdStarted = new CountDownLatch(1);
        when(lifecycle.start(first)).thenReturn(Optional.of(run));
        when(lifecycle.start(second)).thenReturn(Optional.of(run));
        when(lifecycle.start(third)).thenReturn(Optional.of(run));
        when(tasks.create(run, first)).thenReturn(firstTask);
        when(tasks.create(run, second)).thenReturn(secondTask);
        when(tasks.create(run, third)).thenReturn(thirdTask);
        when(firstTask.completion()).thenReturn(firstDone.asMono().doOnSubscribe(ignored -> firstStarted.countDown()));
        when(secondTask.completion()).thenReturn(secondDone.asMono().doOnSubscribe(ignored -> secondStarted.countDown()));
        when(thirdTask.completion()).thenReturn(Mono.<Void>empty().doOnSubscribe(ignored -> thirdStarted.countDown()));
        try (var worker = new RunWorker(lifecycle, tasks, null, maximum::get)) {
            worker.poll();
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
            verify(lifecycle, times(1)).claim(anyString());
            maximum.set(2);
            worker.poll();
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
            maximum.set(1);
            firstDone.tryEmitEmpty();
            for (int i = 0; i < 10; i++) {
                worker.poll();
                assertTrue(thirdStarted.getCount() == 1);
                TimeUnit.MILLISECONDS.sleep(10);
            }
            verify(secondTask, never()).cancel();
            verify(lifecycle, times(2)).claim(anyString());
            secondDone.tryEmitEmpty();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (thirdStarted.getCount() != 0 && System.nanoTime() < deadline) {
                worker.poll();
                thirdStarted.await(10, TimeUnit.MILLISECONDS);
            }
            assertTrue(thirdStarted.getCount() == 0);
        }
    }

    @Test
    void cancellationAndCleanupFailuresStillEndTheRunAndReleaseTheWorkerSlot() throws Exception {
        var lifecycle = mock(RunLifecycleService.class);
        var tasks = mock(ExecutionTaskFactory.class);
        var usage = mock(ProjectSandboxUsageService.class);
        var first = mock(JobLease.class);
        var second = mock(JobLease.class);
        when(first.id()).thenReturn("blocked-job");
        when(first.runId()).thenReturn("blocked-run");
        when(second.id()).thenReturn("next-job");
        when(second.runId()).thenReturn("next-run");
        var run = mock(RunRecord.class);
        when(lifecycle.claim(anyString())).thenReturn(Optional.of(first), Optional.of(second), Optional.empty());
        when(lifecycle.start(first)).thenReturn(Optional.of(run));
        when(lifecycle.start(second)).thenReturn(Optional.of(run));
        var blocked = mock(ExecutionTask.class);
        var next = mock(ExecutionTask.class);
        var subscribed = new CountDownLatch(1);
        when(blocked.completion()).thenReturn(Mono.<Void>never().doOnSubscribe(ignored -> subscribed.countDown()));
        when(next.completion()).thenReturn(Mono.empty());
        when(tasks.create(run, first)).thenReturn(blocked);
        when(tasks.create(run, second)).thenReturn(next);
        doThrow(new IllegalStateException("模拟底层取消失败")).when(blocked).cancel();
        doThrow(new IllegalStateException("模拟沙箱使用记录清理失败")).when(usage).release(first);
        var completed = new CountDownLatch(1);
        doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(lifecycle).finish(second, "completed", null, null);
        try (var worker = new RunWorker(lifecycle, tasks, usage, 1)) {
            worker.poll();
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            verify(lifecycle, times(1)).claim(anyString());
            when(lifecycle.shouldStop(first)).thenReturn(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (completed.getCount() != 0 && System.nanoTime() < deadline) {
                worker.poll();
                completed.await(10, TimeUnit.MILLISECONDS);
            }
            assertTrue(completed.getCount() == 0, "异常清理后下一个任务应能领取并结束");
        }
        verify(lifecycle).finish(first, "cancelled", null, null);
        verify(blocked, never()).saveCheckpoint();
        verify(blocked).close();
    }

    @Test
    void handledExecutionFailuresKeepTheOriginalStackAndRunIdentity(CapturedOutput output) throws Exception {
        var lifecycle = mock(RunLifecycleService.class);
        var tasks = mock(ExecutionTaskFactory.class);
        var lease = mock(JobLease.class);
        var run = mock(RunRecord.class);
        when(lease.id()).thenReturn("test-job");
        when(lease.runId()).thenReturn("test-run");
        when(lifecycle.claim(anyString())).thenReturn(Optional.of(lease)).thenReturn(Optional.empty());
        when(lifecycle.start(lease)).thenReturn(Optional.of(run));
        when(tasks.create(run, lease)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,
            "MODEL_TEMPORARY_FAILURE", "模型暂不可用。", new IOException("测试模型连接失败")));
        var finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            finished.countDown();
            return null;
        }).when(lifecycle).rejectClaim(eq(lease), anyString(), anyString());

        try (var worker = new RunWorker(lifecycle, tasks)) {
            worker.poll();
            assertTrue(finished.await(3, TimeUnit.SECONDS), "后台执行应记录失败并完成原有状态处理");
        }
        verify(lifecycle).rejectClaim(lease, "MODEL_TEMPORARY_FAILURE", "模型暂不可用。");
        assertTrue(output.getOut().contains("Conversation execution failed; executionId=test-run, jobId=test-job"));
        assertTrue(output.getOut().contains("Caused by: " + IOException.class.getName() + ": 测试模型连接失败"));
        assertTrue(output.getOut().contains("at " + RunWorker.class.getName() + ".execute("));
    }
}
