package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionEventCache;
import com.stonewu.agenteam.mapper.execution.ExecutionEventMapper;
import com.stonewu.agenteam.mapper.tool.ToolPayloadPreview;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.ExecutionEvent;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.service.auth.ConnectionAuthorization;
import com.stonewu.agenteam.service.http.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每条连接按会话连续序号读取；缓存不可读时检查数据库，不跳过无法确认的事件。
 */
@Service
public class ConversationEventService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationEventService.class);
    private final ConversationQueryService queries;
    private final ConversationMapper conversations;
    private final ExecutionEventMapper events;
    private final ExecutionEventCache cache;
    private final ConnectionAuthorization authorization;
    private final Clock clock;
    private final LiveConversationEventService live;

    public ConversationEventService(ConversationQueryService queries, ConversationMapper conversations,
                                    ExecutionEventMapper events,
                                    ExecutionEventCache cache, ConnectionAuthorization authorization, Clock clock,
                                    LiveConversationEventService live) {
        this.queries = queries;
        this.conversations = conversations;
        this.events = events;
        this.cache = cache;
        this.authorization = authorization;
        this.clock = clock;
        this.live = live;
    }

    public Flux<ServerSentEvent<Object>> connect(AuthContext actor, String sessionId, String conversation, String after,
                                                 String lastEventId) {
        return connect(actor, sessionId, conversation, after, lastEventId, null);
    }

    public Flux<ServerSentEvent<Object>> connect(AuthContext actor, String sessionId, String conversation, String after,
                                                 String lastEventId, String generation) {
        long position = parseAfter(after, lastEventId);
        var row = queries.readable(actor, conversation);
        if (row.liveEvents()) {
            return live.connect(actor, sessionId, conversation, new StreamCursor(generation, Long.toString(position)));
        }
        return Flux.defer(() -> {
            var reader = new Reader(actor, sessionId, conversation, position, row.lastSequence());
            return Mono.fromCallable(reader::poll).subscribeOn(Schedulers.boundedElastic())
                .repeatWhen(completed -> completed.delayElements(Duration.ofMillis(100)))
                .takeUntil(Batch::complete).concatMapIterable(Batch::events);
        });
    }

    private long parseAfter(String after, String lastEventId) {
        if (after != null && lastEventId != null && !after.equals(lastEventId)) {
            throw invalidAfter();
        }
        String value = after == null ? lastEventId : after;
        if (value == null) {
            return 0;
        }
        if (value.length() > 19 || !value.matches("0|[1-9][0-9]*")) {
            throw invalidAfter();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw invalidAfter();
        }
    }

    private ApiException invalidAfter() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "事件编号格式不正确，请重新打开对话。");
    }

    private record Batch(List<ServerSentEvent<Object>> events, boolean complete) {
    }

    private final class Reader {
        private final AuthContext actor;
        private final String sessionId;
        private final String conversation;
        private final long boundary;
        private long cursor;
        private boolean ready;
        private long checkedAt;
        private long heartbeatAt;
        private long nextCacheWarningAt = Long.MIN_VALUE;

        private Reader(AuthContext actor, String sessionId, String conversation, long after, long boundary) {
            this.actor = actor;
            this.sessionId = sessionId;
            this.conversation = conversation;
            this.cursor = after;
            this.boundary = boundary;
            this.checkedAt = clock.millis();
            this.heartbeatAt = clock.millis();
        }

        private Batch poll() {
            long now = clock.millis();
            if (now - checkedAt >= 15000) {
                checkedAt = now;
                if (!authorization.allowed(sessionId, actor, "conversation.view")) {
                    return new Batch(List.of(), true);
                }
            }
            var current = conversations.streamPosition(actor.enterpriseId(), actor.userId(), conversation,
                clock.instant().minus(Duration.ofDays(7))).orElse(null);
            if (current == null || current.status().equals("deleted")) {
                return new Batch(List.of(), true);
            }
            if (cursor > current.lastSequence()) {
                return reset("sequence_invalid", current.lastSequence());
            }
            List<ServerSentEvent<Object>> output = new ArrayList<>();
            addReady(output);
            if (cursor < current.lastSequence()) {
                List<ExecutionEvent> batch;
                try {
                    batch = read(current.lastSequence());
                } catch (IllegalStateException invalid) {
                    LOG.warn("对话事件无法读取，将通知页面重新加载完整快照，会话编号 {}，上次事件编号 {}", conversation,
                        cursor, invalid);
                    return reset("data_incomplete", current.lastSequence());
                }
                if (batch.isEmpty() || Long.parseLong(batch.getFirst().sequence()) != cursor + 1) {
                    long oldest = events.firstSequence(actor.enterpriseId(), conversation);
                    return reset(oldest == 0 || oldest > cursor + 1 ? "events_expired" : "data_incomplete",
                        current.lastSequence());
                }
                for (var event : batch) {
                    if (event.protocolVersion() != 1) {
                        return reset("protocol_changed", current.lastSequence());
                    }
                    long sequence = Long.parseLong(event.sequence());
                    if (sequence != cursor + 1 || !Objects.equals(actor.enterpriseId(),
                        event.enterpriseId()) || !conversation.equals(event.conversationId())) {
                        return reset("data_incomplete", current.lastSequence());
                    }
                    output.add(ServerSentEvent.builder((Object) ToolPayloadPreview.event(event)).id(event.sequence())
                        .event(event.type()).build());
                    cursor = sequence;
                    addReady(output);
                }
            }
            if (now - heartbeatAt >= 15000) {
                output.add(ServerSentEvent.builder().comment("保持连接").build());
                heartbeatAt = now;
            }
            return new Batch(output, ready && cursor >= current.lastSequence() && current.activeRunId() == null);
        }

        private List<ExecutionEvent> read(long lastSequence) {
            try {
                var cached = cache.after(actor.enterpriseId(), conversation, cursor, 100);
                nextCacheWarningAt = Long.MIN_VALUE;
                if (continuous(cached, lastSequence)) {
                    return cached;
                }
            } catch (RuntimeException unavailable) {
                // 缓存故障或内容不一致时，继续读取已提交的数据库记录。
                // 同一连接持续读取时，每三十秒记录一次完整堆栈，避免缓存故障产生大量重复日志。
                long now = clock.millis();
                if (now >= nextCacheWarningAt) {
                    nextCacheWarningAt = now + 30000;
                    LOG.warn("对话事件缓存读取失败，将继续读取数据库，会话编号 {}，上次事件编号 {}", conversation, cursor,
                        unavailable);
                }
            }
            return events.after(actor.enterpriseId(), conversation, cursor, 100).stream()
                .filter(value -> Long.parseLong(value.sequence()) <= lastSequence).toList();
        }

        private boolean continuous(List<ExecutionEvent> values, long lastSequence) {
            if (values.isEmpty()) {
                return false;
            }
            long expected = cursor;
            for (var value : values) {
                if (Long.parseLong(value.sequence()) != ++expected || expected > lastSequence) {
                    return false;
                }
            }
            return true;
        }

        private void addReady(List<ServerSentEvent<Object>> output) {
            if (!ready && cursor >= boundary) {
                output.add(ServerSentEvent.builder((Object) Map.of("lastSequence", Long.toString(boundary)))
                    .event("stream.ready").build());
                ready = true;
            }
        }

        private Batch reset(String reason, long lastSequence) {
            return new Batch(List.of(
                ServerSentEvent.builder((Object) Map.of("reason", reason, "lastSequence", Long.toString(lastSequence)))
                    .event("stream.reset").build()), true);
        }
    }
}
