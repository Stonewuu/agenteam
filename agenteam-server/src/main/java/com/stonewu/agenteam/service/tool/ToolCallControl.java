package com.stonewu.agenteam.service.tool;

import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import com.stonewu.tool.api.ToolCancellation;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个工具调用的网络连接共享取消信号，取消不表示远程写入已经撤回。
 */
public final class ToolCallControl implements AutoCloseable, ToolCancellation {
    private final Set<AutoCloseable> resources = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Runnable beforeSend;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public ToolCallControl(Runnable beforeSend) {
        this.beforeSend = beforeSend;
    }

    public void beforeSend() {
        requireActive();
        beforeSend.run();
        requireActive();
    }

    public void requireActive() {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new ExecutionStoppedException();
        }
    }

    public <T extends AutoCloseable> T track(T resource) {
        resources.add(resource);
        if (closed.get()) {
            release(resource);
            throw new ExecutionStoppedException();
        }
        return resource;
    }

    public void pause(Duration duration) {
        requireActive();
        try {
            stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        requireActive();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopped.countDown();
            resources.forEach(this::release);
        }
    }

    private void release(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ignored) { /* 当前连接已停止使用，调用状态由执行事务确定。 */ }
    }
}
