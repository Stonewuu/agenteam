package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ModelFailureMapper;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectSandboxUsageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工作进程只领取已经提交的任务，重启后从数据库继续检查未完成记录。
 */
@Component
@ConditionalOnProperty(name = "execution.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RunWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RunWorker.class);
    private final String owner = "worker-" + UUID.randomUUID();
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(10, task -> {
        Thread thread = new Thread(task, "execution-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RunLifecycleService lifecycle;
    private final ExecutionTaskFactory tasks;
    private final ProjectSandboxUsageService sandboxUsage;

    public RunWorker(RunLifecycleService lifecycle, ExecutionTaskFactory tasks) {
        this(lifecycle, tasks, null);
    }

    @Autowired
    public RunWorker(RunLifecycleService lifecycle, ExecutionTaskFactory tasks,
                     ProjectSandboxUsageService sandboxUsage) {
        this.lifecycle = lifecycle;
        this.tasks = tasks;
        this.sandboxUsage = sandboxUsage;
    }

    private static final class Active {
        private final JobLease lease;
        private volatile ExecutionTask task;
        private volatile boolean started;
        private volatile boolean stopping;
        private long renewedAt = System.nanoTime();

        private Active(JobLease lease) {
            this.lease = lease;
        }

        private void cancel() {
            stopping = true;
            var current = task;
            if (current != null) {
                current.cancel();
            }
        }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void poll() {
        if (closed.get()) {
            return;
        }
        try {
            for (var running : active.values()) {
                maintain(running);
            }
            for (var lease : lifecycle.expired()) {
                lifecycle.recover(lease);
            }
            for (var lease : lifecycle.queueTimeouts()) {
                lifecycle.expireQueue(lease);
            }
            for (var approval : lifecycle.expiredApprovals()) {
                lifecycle.expireApproval(approval);
            }
            while (!closed.get() && active.size() < 10) {
                var claimed = lifecycle.claim(owner);
                if (claimed.isEmpty()) {
                    break;
                }
                var running = new Active(claimed.get());
                active.put(running.lease.id(), running);
                executor.submit(() -> execute(running));
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("后台执行检查暂时未完成，下一轮将继续检查", unavailable);
        }
    }

    private void maintain(Active running) {
        if (running.started && lifecycle.shouldStop(running.lease)) {
            running.cancel();
        }
        if (System.nanoTime() - running.renewedAt >= 10_000_000_000L) {
            boolean held = running.started ? lifecycle.recheckAndRenew(running.lease) : lifecycle.renew(running.lease);
            if (!held) {
                running.cancel();
            } else if (sandboxUsage != null) {
                sandboxUsage.renew(running.lease);
            }
            running.renewedAt = System.nanoTime();
        }
    }

    private void execute(Active running) {
        try {
            var record = lifecycle.start(running.lease);
            if (record.isEmpty()) {
                return;
            }
            running.started = true;
            if (sandboxUsage != null) {
                sandboxUsage.started(running.lease);
            }
            try (var task = tasks.create(record.get(), running.lease)) {
                running.task = task;
                if (running.stopping || closed.get()) {
                    task.cancel();
                }
                task.completion().block();
                task.saveCheckpoint();
                if (task.waiting()) {
                    return;
                }
                if (closed.get()) {
                    finish(running, "failed", "EXECUTION_INTERRUPTED", "本次执行已中断，请重新执行。");
                } else {
                    finish(running, task.cancelled() ? "cancelled" : "completed", null, null);
                }
            }
        } catch (Throwable failure) {
            Throwable cause = Exceptions.unwrap(failure);
            if (!(cause instanceof ExecutionStoppedException)) {
                LOG.error("对话执行失败，执行编号 {}，工作编号 {}", running.lease.runId(), running.lease.id(), failure);
            }
            if (cause instanceof ExecutionStoppedException) {
                finish(running, "cancelled", null, null);
            } else if (cause instanceof ApiException known) {
                reject(running, known.code(), known.getReason());
            } else if (cause instanceof TimeoutException) {
                reject(running, "EXECUTION_TIMEOUT", "本次执行已超时，已保存的内容仍可查看。");
            } else {
                var model = ModelFailureMapper.map(cause);
                if (model.isPresent()) {
                    reject(running, model.get().code(), model.get().getReason());
                } else {
                    reject(running, "EXECUTION_FAILED", "本次执行未能完成，请稍后重试。");
                }
            }
        } finally {
            if (sandboxUsage != null) {
                sandboxUsage.release(running.lease);
            }
            active.remove(running.lease.id());
        }
    }

    private void finish(Active running, String status, String code, String message) {
        try {
            lifecycle.finish(running.lease, status, code, message);
        } catch (RuntimeException unavailable) {
            LOG.warn("执行 {} 的结束状态暂时无法保存，后台将继续检查", running.lease.runId(), unavailable);
        }
    }

    private void reject(Active running, String code, String message) {
        try {
            lifecycle.rejectClaim(running.lease, code, message);
        } catch (RuntimeException unavailable) {
            LOG.warn("执行 {} 的失败状态暂时无法保存，后台将继续检查", running.lease.runId(), unavailable);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        closed.set(true);
        active.values().forEach(Active::cancel);
        executor.shutdown();
    }
}
