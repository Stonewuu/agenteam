package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.service.background.BackgroundJobHeartbeat;
import com.stonewu.agenteam.service.background.BackgroundJobService;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久领取后读取数据、写文件并登记；续期丢失后不能覆盖另一工作进程的结果。
 */
@Component
public class ExportWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExportWorker.class);
    private final BackgroundJobService jobs;
    private final BackgroundJobHeartbeat heartbeats;
    private final ExportSnapshotService snapshots;
    private final ExportFileService files;
    private final ExportJobTransactions transactions;
    private final boolean enabled;
    private final String worker = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newFixedThreadPool(2,
        Thread.ofVirtual().name("数据导出-", 0).factory());
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ExportWorker(BackgroundJobService jobs, BackgroundJobHeartbeat heartbeats, ExportSnapshotService snapshots,
                        ExportFileService files, ExportJobTransactions transactions,
                        @Value("${exports.enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.heartbeats = heartbeats;
        this.snapshots = snapshots;
        this.files = files;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!enabled || closed.get()) {
            return;
        }
        try {
            while (active.get() < 2 && !closed.get()) {
                var lease = jobs.claimExport(worker);
                if (lease.isEmpty()) {
                    return;
                }
                active.incrementAndGet();
                executor.execute(() -> {
                    try {
                        process(lease.get());
                    } finally {
                        active.decrementAndGet();
                    }
                });
            }
        } catch (RuntimeException unavailable) {
            LOG.warn("导出领取暂未完成，下一轮将继续检查", unavailable);
        }
    }

    public boolean runNext() {
        var lease = jobs.claimExport(worker);
        lease.ifPresent(this::process);
        return lease.isPresent();
    }

    public void process(JobLease lease) {
        PreparedGeneratedFile file = null;
        boolean committed = false, completionAttempted = false, completionKnown = false;
        try (var guard = heartbeats.start(lease)) {
            if (lease.exhausted()) {
                transactions.failed(lease, "EXPORT_INTERRUPTED", "导出执行多次中断，请重新发起。", false);
                return;
            }
            if (!guard.getAsBoolean()) {
                return;
            }
            var snapshot = snapshots.read(lease);
            if (!guard.getAsBoolean() || closed.get()) {
                return;
            }
            file = files.write(lease.enterpriseId(), lease.ownerUserId(), snapshot,
                () -> guard.getAsBoolean() && !closed.get());
            completionAttempted = true;
            committed = transactions.complete(lease, snapshot, file);
            completionKnown = true;
        } catch (ResponseStatusException denied) {
            LOG.warn("数据导出未完成，工作编号 {}", lease.id(), denied);
            completionKnown = true;
            String code = denied instanceof ApiException api ? api.code() : "EXPORT_UNAVAILABLE";
            if (code.equals("EXPORT_LIMIT_EXCEEDED")) {
                transactions.failed(lease, code, denied.getReason(), false);
            } else if (denied.getStatusCode().is4xxClientError()) {
                transactions.failed(lease, "EXPORT_UNAVAILABLE", "当前已无法读取所选数据，请按现有权限重新导出。", false);
            } else {
                transactions.failed(lease, "EXPORT_TEMPORARILY_UNAVAILABLE", "导出暂未完成，正在等待重试。", true);
            }
        } catch (RuntimeException failed) {
            LOG.error("数据导出失败，工作编号 {}", lease.id(), failed);
            transactions.failed(lease, "EXPORT_TEMPORARILY_UNAVAILABLE", "导出暂未完成，请稍后重试。", true);
        } finally {
            // 提交应答不确定时交给后续清理核对数据库，不能删除可能已经提交的有效结果。
            if (file != null && !committed && (!completionAttempted || completionKnown)) {
                try {
                    files.discard(file);
                } catch (RuntimeException unavailable) {
                    LOG.warn("未登记的导出文件暂未清理，后台文件清理将继续处理，工作编号 {}", lease.id(), unavailable);
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
