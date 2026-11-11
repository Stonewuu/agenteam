package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.execution.*;
import com.stonewu.agenteam.model.execution.entity.CommittedExecutionEvent;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 数据库提交后再分发，不在持有会话行锁时等待 Redis。
 */
@Service
public class ExecutionEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(ExecutionEventPublisher.class);
    private final ExecutionEventMapper events;
    private final ConversationMapper conversations;
    private final ExecutionEventCache cache;
    private final ExecutionEventCodec codec;
    private final Clock clock;
    private final RunMapper runs;
    private final LiveEventDelivery live;
    private final ThreadPoolExecutor direct = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(512), Thread.ofVirtual().name("legacy-event-delivery-", 0).factory());
    @Value("${execution.events.worker-enabled:true}")
    private boolean automatic;
    private volatile long lastWarning;

    public ExecutionEventPublisher(ExecutionEventMapper events, ConversationMapper conversations,
                                   ExecutionEventCache cache, ExecutionEventCodec codec, Clock clock,
                                   RunMapper runs, LiveEventDelivery live) {
        this.events = events;
        this.conversations = conversations;
        this.cache = cache;
        this.codec = codec;
        this.clock = clock;
        this.runs = runs;
        this.live = live;
    }

    public void publishPending() {
        try {
            for (var conversation : events.pendingConversations(50)) {
                publish(conversation.enterpriseId(), conversation.conversationId());
            }
        } catch (RuntimeException unavailable) {
            if (clock.millis() - lastWarning >= 30000) {
                lastWarning = clock.millis();
                LOG.warn(Messages.log("log.execution.executionEventPublisher.publishPending.1"), unavailable);
            }
        }
    }

    public void publish(String enterprise, String conversation) {
        if ("redis".equals(conversations.eventDeliveryMode(enterprise, conversation))) {
            var pending = events.unpublished(enterprise, conversation, 1);
            if (!pending.isEmpty()) {
                runs.find(enterprise, pending.getFirst().runId(), false).ifPresent(live::recover);
            }
            return;
        }
        String owner = cache.claim(enterprise, conversation);
        if (owner == null) {
            return;
        }
        try {
            var position = cache.last(enterprise, conversation);
            long current = conversations.lastSequence(enterprise, conversation);
            long after = position.sequence();
            if (after > current) {
                throw new IllegalStateException(Messages.text("error.execution.executionEventPublisher.publish.1"));
            }
            if (after > 0) {
                var saved = events.after(enterprise, conversation, after - 1, 1);
                if (!saved.isEmpty() && Long.parseLong(saved.getFirst().sequence()) == after
                    && !codec.encode(saved.getFirst()).hash().equals(position.hash())) {
                    throw new IllegalStateException(Messages.text("error.execution.executionEventPublisher.publish.2"));
                }
            } else {
                after = Math.max(Math.max(0, events.firstSequence(enterprise, conversation) - 1), current - 10000);
            }
            var batch = events.after(enterprise, conversation, after, 100);
            if (!batch.isEmpty()) {
                if (!cache.append(enterprise, conversation, owner, batch)) {
                    return;
                }
                after = Long.parseLong(batch.getLast().sequence());
            }
            if (after > 0) {
                events.publishedThrough(enterprise, conversation, after, clock.instant());
            }
        } finally {
            cache.release(enterprise, conversation, owner);
        }
    }

    public void removeExpired() {
        events.removeExpired(clock.instant().minus(Duration.ofDays(7)));
    }

    @TransactionalEventListener
    public void committed(CommittedExecutionEvent saved) {
        if (!saved.live() && automatic) {
            try {
                direct.execute(() -> publishDirect(saved.event()));
            } catch (RejectedExecutionException full) {
                LOG.warn(Messages.log("log.execution.executionEventPublisher.committed.1"), saved.event().conversationId(), full);
            }
        }
    }

    private void publishDirect(ExecutionEvent event) {
        String owner = null;
        try {
            owner = cache.claim(event.enterpriseId(), event.conversationId());
            if (owner == null) {
                return;
            }
            var position = cache.last(event.enterpriseId(), event.conversationId());
            long sequence = Long.parseLong(event.sequence());
            if (position.sequence() == sequence - 1 && cache.append(event.enterpriseId(), event.conversationId(), owner,
                List.of(event))) {
                events.published(event, clock.instant());
            }
        } catch (RuntimeException unavailable) {
            LOG.warn(Messages.log("log.execution.executionEventPublisher.publishDirect.1"), event.conversationId(),
                event.eventId(), unavailable);
        } finally {
            if (owner != null) {
                try {
                    cache.release(event.enterpriseId(), event.conversationId(), owner);
                } catch (RuntimeException unavailable) {
                    LOG.warn(Messages.log("log.execution.executionEventPublisher.publishDirect.2"), event.conversationId(), event.eventId(),
                        unavailable);
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        direct.shutdown();
    }
}
