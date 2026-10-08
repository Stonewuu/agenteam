package com.stonewu.agenteam.service.background;

import com.stonewu.agenteam.model.background.entity.JobLease;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 长工作持续续期，失去数据库领取资格后停止向业务表提交结果。
 */
@Service
public class BackgroundJobHeartbeat {
    private static final Logger LOG = LoggerFactory.getLogger(BackgroundJobHeartbeat.class);
    private final BackgroundJobService jobs;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(4,
        Thread.ofPlatform().daemon(true).name("后台工作续期-", 0).factory());

    public BackgroundJobHeartbeat(BackgroundJobService jobs) {
        this.jobs = jobs;
    }

    public Guard start(JobLease lease) {
        AtomicBoolean valid = new AtomicBoolean(jobs.renew(lease));
        ScheduledFuture<?> future = timer.scheduleWithFixedDelay(() -> {
            try {
                if (!jobs.renew(lease)) {
                    valid.set(false);
                }
            } catch (RuntimeException lost) {
                valid.set(false);
                LOG.warn("后台工作续期失败，工作编号 {}", lease.id(), lost);
            }
        }, 5, 5, TimeUnit.SECONDS);
        return new Guard(valid, future);
    }

    public record Guard(AtomicBoolean valid, ScheduledFuture<?> future) implements BooleanSupplier, AutoCloseable {
        @Override
        public boolean getAsBoolean() {
            return valid.get() && !Thread.currentThread().isInterrupted();
        }

        @Override
        public void close() {
            valid.set(false);
            future.cancel(false);
        }
    }

    @PreDestroy
    public void close() {
        timer.shutdownNow();
    }
}
