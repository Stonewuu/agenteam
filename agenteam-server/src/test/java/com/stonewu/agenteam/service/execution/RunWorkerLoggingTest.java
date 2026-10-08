package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class RunWorkerLoggingTest {
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
        assertTrue(output.getOut().contains("对话执行失败，执行编号 test-run，工作编号 test-job"));
        assertTrue(output.getOut().contains("Caused by: " + IOException.class.getName() + ": 测试模型连接失败"));
        assertTrue(output.getOut().contains("at " + RunWorker.class.getName() + ".execute("));
    }
}
