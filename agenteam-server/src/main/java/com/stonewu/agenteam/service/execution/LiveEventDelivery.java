package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.mapper.execution.LiveEventCodec;
import com.stonewu.agenteam.model.execution.entity.*;
import com.stonewu.agenteam.model.execution.response.ContentBlock;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.service.execution.RunLifecycleService.ExecutionStoppedException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 流式正文直接写 Redis；业务事务提交后发送变化，遗漏通知时读取完整业务结果。
 */
@Service
public class LiveEventDelivery {
    private static final Logger LOG = LoggerFactory.getLogger(LiveEventDelivery.class);
    private final LiveEventCache cache;
    private final LiveEventCodec codec;
    private final LiveDatabaseStateReader database;
    private final Clock clock;
    private final Map<String, LiveExecutionSession> sessions = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[64];
    private final ThreadPoolExecutor notifications = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(512), Thread.ofVirtual().name("live-event-notification-", 0).factory());

    public LiveEventDelivery(LiveEventCache cache, LiveEventCodec codec, LiveDatabaseStateReader database,
                             Clock clock) {
        this.cache = cache;
        this.codec = codec;
        this.database = database;
        this.clock = clock;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public LiveExecutionSession open(RunRecord run, JobLease lease, List<ContentBlock> initial) {
        database.requireLease(run, lease);
        var session = new LiveExecutionSession(this, run, lease, initial, clock);
        var old = sessions.putIfAbsent(LiveEventCache.token(lease), session);
        if (old != null) {
            session.close();
            throw new IllegalStateException(Messages.text("error.execution.liveEventDelivery.open.1"));
        }
        return session;
    }

    public void closed(JobLease lease, LiveExecutionSession session) {
        sessions.remove(LiveEventCache.token(lease), session);
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
            throw new IllegalStateException(Messages.text("error.execution.liveEventDelivery.ensure.1"));
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
                throw new IllegalStateException(Messages.text("error.execution.liveEventDelivery.append.1", result.status()));
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
            throw new IllegalStateException(Messages.text("error.execution.liveEventDelivery.restore.1"));
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
                    || List.of("pending", "running", "waiting_approval", "waiting_input").contains(block.status()))
                .map(ExecutionChange::replace).toList();
            return append(run, lease, cursor, changes(run, restore));
        } finally {
            cache.release(run.enterpriseId(), run.conversationId(), owner);
        }
    }

    @EventListener
    public void committed(CommittedConversationChanges saved) {
        dispatch(saved.run(), () -> publish(saved));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void renewed(ExecutionLeaseRenewed renewed) {
        var session = sessions.get(LiveEventCache.token(renewed.lease()));
        if (session != null) {
            session.renew(renewed.until());
        }
    }

    public void publish(CommittedConversationChanges saved) {
        var run = saved.run();
        ReentrantLock lock = lock(run);
        lock.lock();
        try {
            var state = ensure(run);
            if (saved.version() <= state.appliedDatabaseVersion()) {
                return;
            }
            var changes = new LinkedHashMap<String, LiveEventCodec.Mutation>();
            saved.events().stream().map(codec::committed).forEach(change -> changes.put(change.objectId(), change));
            var result = cache.commit(run.enterpriseId(), run.conversationId(), state.cursor(), saved.version(),
                List.copyOf(changes.values()));
            if (!result.accepted()) {
                recoverLocked(run);
            }
            if (saved.events().stream().anyMatch(event -> List.of("run.completed", "run.failed", "run.cancelled")
                .contains(event.type()))) {
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
        recover(run.enterpriseId(), run.userId(), run.conversationId());
    }

    public void recover(String enterprise, String user, String conversation) {
        String owner = cache.claim(enterprise, conversation);
        if (owner == null) {
            return;
        }
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                var previous = cache.snapshot(enterprise, conversation);
                var baseline = database.recovery(enterprise, user, conversation);
                if (previous.isEmpty()) {
                    cache.initialize(enterprise, conversation, owner, baseline.conversation().lastSequence(),
                        baseline.conversation().activeRunId(), null);
                    return;
                }
                if (cache.reconcile(baseline.conversation(), previous.get(), owner, baseline.outputMessage(),
                    baseline.revisions(), clock.instant())) {
                    return;
                }
            }
            throw new IllegalStateException("Conversation changed during cache recovery");
        } finally {
            cache.release(enterprise, conversation, owner);
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
                    LOG.warn(Messages.log("log.execution.liveEventDelivery.dispatch.1"), run.conversationId(),
                        run.id(), failed);
                }
            });
        } catch (RejectedExecutionException full) {
            LOG.warn(Messages.log("log.execution.liveEventDelivery.dispatch.2"), run.conversationId(), run.id(), full);
        }
    }

    @PreDestroy
    public void close() {
        notifications.shutdown();
    }
}
