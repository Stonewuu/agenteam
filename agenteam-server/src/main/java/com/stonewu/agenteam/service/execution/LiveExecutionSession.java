package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.LiveEventCodec;
import com.stonewu.agenteam.model.execution.entity.ExecutionChange;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 同次执行分别按序发送和保存；网络与数据库等待不占用内容转换锁。
 */
public final class LiveExecutionSession implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LiveExecutionSession.class);
    private final LiveEventDelivery delivery;
    private final RunRecord run;
    private final JobLease lease;
    private final Clock clock;
    private final ThreadPoolExecutor sender = executor("live-output-", 128);
    private final ThreadPoolExecutor saver = executor("completed-block-save-", 64);
    private final Map<String, String> scheduledVersions = new HashMap<>();
    private final Sinks.Empty<Void> failure = Sinks.empty();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile List<ContentBlock> current;
    private volatile Instant until;
    private volatile RuntimeException error;
    private CompletableFuture<Void> sent = CompletableFuture.completedFuture(null);
    private CompletableFuture<Void> saved = CompletableFuture.completedFuture(null);
    private StreamCursor cursor;
    private Instant confirmedUntil;
    private boolean restore;
    private long retryAt;
    private long warnAt;
    private Map<String, String> restoredVersions = Map.of();

    public LiveExecutionSession(LiveEventDelivery delivery, RunRecord run, JobLease lease,
                                List<ContentBlock> initial, Clock clock) {
        this.delivery = delivery;
        this.run = run;
        this.lease = lease;
        this.clock = clock;
        this.current = List.copyOf(initial);
        this.until = lease.until();
        initial.forEach(block -> scheduledVersions.put(block.id(), block.revision()));
    }

    private static ThreadPoolExecutor executor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(capacity),
            Thread.ofVirtual().name(name, 0).factory());
    }

    public Sinks.Empty<Void> failure() {
        return failure;
    }

    public void requireOpen() {
        if (error != null) {
            throw error;
        }
        if (closed.get()) {
            throw new IllegalStateException("执行事件入口已经关闭");
        }
    }

    public synchronized void publish(List<ExecutionChange> changes, List<ContentBlock> content) {
        requireOpen();
        current = List.copyOf(content);
        var mutations = delivery.changes(run, changes);
        if (!mutations.isEmpty()) {
            sent = submit(sender, () -> send(mutations));
        }
    }

    /**
     * 保存命令在内容转换锁内建立，实际事务在独立队列中执行。
     */
    public synchronized void persist(List<ExecutionChange> changes, BiConsumer<String, List<ExecutionChange>> commit,
                                     boolean saveEmpty) {
        requireOpen();
        if (changes.isEmpty() && !saveEmpty) {
            return;
        }
        var completed = changes.stream().map(change -> {
            if (change.block() == null) {
                return change;
            }
            if (change.delta() != null) {
                throw new IllegalArgumentException("数据库只能接收完整内容块");
            }
            String expected = scheduledVersions.getOrDefault(change.block().id(), "0");
            scheduledVersions.put(change.block().id(), change.block().revision());
            return new ExecutionChange(change.block(), null, expected, null, change.result(), change.input());
        }).toList();
        String batch = UUID.randomUUID().toString();
        var beforeCommit = sent;
        saved = submit(saver, () -> {
            // 先完成本块已有的发送尝试；Redis 不可用由发送器记录，不阻止完整块入库。
            await(beforeCommit);
            retrySave(batch, completed, commit);
        });
    }

    public void renew(Instant renewedUntil) {
        until = renewedUntil;
        if (!closed.get()) {
            synchronized (this) {
                if (!closed.get()) {
                    sent = submit(sender, () -> send(List.of()));
                }
            }
        }
    }

    private void send(List<LiveEventCodec.Mutation> changes) {
        if (clock.millis() < retryAt) {
            return;
        }
        try {
            if (restore) {
                var content = current;
                cursor = delivery.restore(run, lease, until, content);
                restoredVersions = content.stream().collect(Collectors.toMap(
                    block -> "block:" + run.outputMessageId() + ":" + block.id(), ContentBlock::revision));
                restore = false;
                confirmedUntil = until;
            } else if (cursor == null || !until.equals(confirmedUntil)) {
                var latestUntil = until;
                var confirmed = delivery.start(run, lease, latestUntil);
                if (cursor != null && !cursor.generation().equals(confirmed.generation())) {
                    restore = true;
                    send(changes);
                    return;
                }
                cursor = confirmed;
                confirmedUntil = latestUntil;
            }
            var pending = changes.stream().filter(change -> !restoredVersions.containsKey(change.objectId())
                    || Long.parseLong(change.revision()) > Long.parseLong(restoredVersions.get(change.objectId())))
                .toList();
            cursor = delivery.append(run, lease, cursor, pending);
            retryAt = 0;
        } catch (ExecutionStoppedException stopped) {
            throw stopped;
        } catch (RuntimeException unavailable) {
            restore = true;
            retryAt = clock.millis() + 1000;
            if (clock.millis() >= warnAt) {
                warnAt = clock.millis() + 30000;
                LOG.warn("当前输出暂时无法发送，将保留内存内容并按块保存，会话编号 {}，执行编号 {}",
                    run.conversationId(), run.id(), unavailable);
            }
        }
    }

    private void retrySave(String batch, List<ExecutionChange> changes,
                           BiConsumer<String, List<ExecutionChange>> commit) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        long delay = 200;
        while (true) {
            try {
                commit.accept(batch, changes);
                return;
            } catch (DataAccessException unavailable) {
                LOG.warn("完整内容块暂时保存失败，执行编号 {}，保存批次 {}", run.id(), batch, unavailable);
                if (System.nanoTime() >= deadline) {
                    throw unavailable;
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    unavailable.addSuppressed(interrupted);
                    throw unavailable;
                }
                delay = Math.min(delay * 2, 5000);
            }
        }
    }

    private CompletableFuture<Void> submit(ThreadPoolExecutor executor, Runnable action) {
        var result = new CompletableFuture<Void>();
        try {
            executor.execute(() -> {
                try {
                    if (error != null) {
                        throw error;
                    }
                    action.run();
                    result.complete(null);
                } catch (RuntimeException failed) {
                    error = failed;
                    result.completeExceptionally(failed);
                    fail(failed);
                }
            });
        } catch (RejectedExecutionException full) {
            error = full;
            result.completeExceptionally(full);
            fail(full);
            throw full;
        }
        return result;
    }

    private void fail(RuntimeException failed) {
        error = failed;
        LOG.error("执行内容处理失败，执行编号 {}，工作编号 {}", run.id(), lease.id(), failed);
        failure.tryEmitError(failed);
    }

    public void drain() {
        CompletableFuture<Void> sendComplete;
        CompletableFuture<Void> saveComplete;
        synchronized (this) {
            sendComplete = sent;
            saveComplete = saved;
        }
        await(sendComplete);
        await(saveComplete);
        if (error != null) {
            throw error;
        }
    }

    private static void await(CompletableFuture<Void> value) {
        try {
            value.join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("执行内容处理未完成", failed.getCause());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                drain();
            } finally {
                sender.shutdown();
                saver.shutdown();
                delivery.closed(lease, this);
            }
        }
    }
}
