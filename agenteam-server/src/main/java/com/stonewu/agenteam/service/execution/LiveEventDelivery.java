package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.mapper.execution.LiveEventCodec;
import com.stonewu.agenteam.model.execution.entity.*;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 正文直接写 Redis；数据库完成事件在提交后分发，失败时按需读取完整事件恢复。
 */
@Service
public class LiveEventDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(LiveEventDelivery.class);
    private final LiveEventCache cache;
    private final LiveEventCodec codec;
    private final LiveDatabaseStateReader database;
    private final ExecutionEventMapper events;
    private final Clock clock;
    private final Map<String, LiveExecutionSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[64];
    private final ThreadPoolExecutor notifications = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(512), Thread.ofVirtual().name("live-event-notification-", 0).factory());

    public LiveEventDelivery(LiveEventCache cache, LiveEventCodec codec, LiveDatabaseStateReader database,
                             ExecutionEventMapper events, Clock clock) {
        this.cache = cache;
        this.codec = codec;
        this.database = database;
        this.events = events;
        this.clock = clock;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public LiveExecutionSession open(RunRecord run, JobLease lease, List<ContentBlock> initial) {
        database.requireLease(run, lease);
        var session = new LiveExecutionSession(this, run, lease, initial, clock);
        var old = sessions.putIfAbsent(lease.id(), session);
        if (old != null) {
            session.close();
            throw new IllegalStateException("同一次执行资格不能创建多个独立事件入口");
        }
        return session;
    }

    public void closed(JobLease lease, LiveExecutionSession session) {
        sessions.remove(lease.id(), session);
    }

    public LiveConversationState ensure(RunRecord run) {
        var existing = cache.position(run.enterpriseId(), run.conversationId());
        if (existing.isPresent()) {
            return existing.get();
        }
        return ensure(database.conversation(run.enterpriseId(), run.userId(), run.conversationId()));
    }

    public LiveConversationState ensure(ConversationRecord conversation) {
        var existing = cache.position(conversation.enterpriseId(), conversation.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        String owner = cache.claim(conversation.enterpriseId(), conversation.id());
        if (owner == null) {
            throw new IllegalStateException("对话实时内容正在初始化");
        }
        try {
            var baseline = database.conversation(conversation.enterpriseId(), conversation.userId(), conversation.id());
            cache.initialize(conversation.enterpriseId(), conversation.id(), owner, baseline.lastSequence(),
                baseline.activeRunId(), null);
            return cache.position(conversation.enterpriseId(), conversation.id()).orElseThrow();
        } finally {
            cache.release(conversation.enterpriseId(), conversation.id(), owner);
        }
    }

    public StreamCursor start(RunRecord run, JobLease lease, Instant until) {
        var state = ensure(run);
        if (!run.id().equals(state.activeRunId())) {
            recover(run);
            state = cache.position(run.enterpriseId(), run.conversationId()).orElseThrow();
        }
        if (!cache.renew(run.enterpriseId(), run.conversationId(), state.cursor(), lease, until, clock.instant())) {
            throw new ExecutionStoppedException();
        }
        return state.cursor();
    }

    public StreamCursor append(RunRecord run, JobLease lease, StreamCursor cursor,
                               List<LiveEventCodec.Mutation> changes) {
        var position = cursor;
        for (var change : changes) {
            var result = cache.append(run.enterpriseId(), run.conversationId(), position, LiveEventCache.token(lease),
                change);
            if (result.status().equals("lease_lost")) {
                throw new ExecutionStoppedException();
            }
            if (!result.accepted()) {
                throw new IllegalStateException("实时内容需要重新读取，原因 " + result.status());
            }
            position = result.cursor();
        }
        return position;
    }

    public List<LiveEventCodec.Mutation> changes(RunRecord run, List<ExecutionChange> changes) {
        return changes.stream().filter(change -> change.block() != null)
            .map(change -> codec.change(run, change, clock.instant())).toList();
    }

    public StreamCursor restore(RunRecord run, JobLease lease, Instant until, List<ContentBlock> current) {
        database.requireLease(run, lease);
        String owner = cache.claim(run.enterpriseId(), run.conversationId());
        if (owner == null) {
            throw new IllegalStateException("其他请求正在恢复对话实时内容");
        }
        try {
            var previous = cache.position(run.enterpriseId(), run.conversationId());
            var baseline = database.read(run);
            String generation = cache.initialize(run.enterpriseId(), run.conversationId(), owner,
                baseline.conversation().lastSequence(), baseline.conversation().activeRunId(),
                previous.map(value -> value.cursor().generation()).orElse(null));
            var cursor = new StreamCursor(generation, "0");
            if (!cache.renew(run.enterpriseId(), run.conversationId(), cursor, lease, until, clock.instant())) {
                throw new ExecutionStoppedException();
            }
            var saved = baseline.blocks().stream().collect(Collectors.toMap(ContentBlock::id, ContentBlock::revision));
            var restore = current.stream().filter(block -> !block.revision().equals(saved.get(block.id()))
                    || List.of("pending", "running", "waiting_approval").contains(block.status()))
                .map(ExecutionChange::replace).toList();
            return append(run, lease, cursor, changes(run, restore));
        } finally {
            cache.release(run.enterpriseId(), run.conversationId(), owner);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(CommittedExecutionEvent saved) {
        if (saved.live()) {
            dispatch(saved.run(), () -> publish(saved.run(), saved.event()));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void renewed(ExecutionLeaseRenewed renewed) {
        var session = sessions.get(renewed.lease().id());
        if (session != null) {
            session.renew(renewed.until());
        }
    }

    public void publish(RunRecord run, ExecutionEvent event) {
        ReentrantLock lock = lock(run);
        lock.lock();
        try {
            var state = ensure(run);
            if (Long.parseLong(event.sequence()) <= state.appliedDatabaseVersion()) {
                events.publishedThrough(run.enterpriseId(), run.conversationId(), state.appliedDatabaseVersion(),
                    clock.instant());
                return;
            }
            var result = cache.append(run.enterpriseId(), run.conversationId(), state.cursor(), null,
                codec.committed(event));
            if (!result.accepted()) {
                recoverLocked(run);
            } else {
                events.publishedThrough(run.enterpriseId(), run.conversationId(), result.databaseVersion(),
                    clock.instant());
            }
            if (event.type().equals("run.completed") || event.type().equals("run.failed") || event.type()
                .equals("run.cancelled")) {
                cache.compact(run.enterpriseId(), run.conversationId());
            }
        } finally {
            lock.unlock();
        }
    }

    public void recover(RunRecord run) {
        ReentrantLock lock = lock(run);
        lock.lock();
        try {
            recoverLocked(run);
        } finally {
            lock.unlock();
        }
    }

    private void recoverLocked(RunRecord run) {
        var state = ensure(run);
        long applied = state.appliedDatabaseVersion();
        for (int page = 0; page < 20; page++) {
            var batch = events.after(run.enterpriseId(), run.conversationId(), applied, 100);
            if (batch.isEmpty()) {
                break;
            }
            for (var event : batch) {
                var result = cache.append(run.enterpriseId(), run.conversationId(), state.cursor(), null,
                    codec.committed(event));
                if (!result.accepted()) {
                    throw new IllegalStateException("已保存事件暂时无法按顺序恢复，原因 " + result.status());
                }
                applied = result.databaseVersion();
            }
        }
        if (applied > 0) {
            events.publishedThrough(run.enterpriseId(), run.conversationId(), applied, clock.instant());
        }
    }

    private ReentrantLock lock(RunRecord run) {
        return locks[Math.floorMod((run.enterpriseId() + ":" + run.conversationId()).hashCode(), locks.length)];
    }

    private void dispatch(RunRecord run, Runnable action) {
        try {
            notifications.execute(() -> {
                try {
                    action.run();
                } catch (RuntimeException failed) {
                    LOG.warn("已保存的执行事件暂时未分发，将按需恢复，会话编号 {}，执行编号 {}", run.conversationId(),
                        run.id(), failed);
                }
            });
        } catch (RejectedExecutionException full) {
            LOG.warn("执行事件通知队列已满，将由后台恢复，会话编号 {}，执行编号 {}", run.conversationId(), run.id(), full);
        }
    }

    @PreDestroy
    public void close() {
        notifications.shutdown();
    }
}
