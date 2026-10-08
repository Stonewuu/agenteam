package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.LiveEventCache;
import com.stonewu.agenteam.mapper.tool.ToolPayloadPreview;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.ConnectionAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 正常连接仅从 Redis 等待事件，数据库只用于低频权限和删除检查。
 */
@Service
public class LiveConversationEventService {
    private static final Logger LOG = LoggerFactory.getLogger(LiveConversationEventService.class);
    private final LiveEventCache cache;
    private final ConversationMapper conversations;
    private final ConnectionAuthorization authorization;
    private final Clock clock;

    public LiveConversationEventService(LiveEventCache cache, ConversationMapper conversations,
                                        ConnectionAuthorization authorization, Clock clock) {
        this.cache = cache;
        this.conversations = conversations;
        this.authorization = authorization;
        this.clock = clock;
    }

    public Flux<ServerSentEvent<Object>> connect(AuthContext actor, String session, String conversation,
                                                 StreamCursor cursor) {
        if (cursor.generation() == null || !cursor.generation().matches("[a-zA-Z0-9-]{1,64}")) {
            return Flux.just(control("stream.reset", "protocol_changed", cursor));
        }
        String requestId = "", path = "", method = "GET";
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var request = attributes.getRequest();
            requestId = String.valueOf(request.getAttribute(AuditEventService.REQUEST_ID_ATTRIBUTE));
            path = request.getRequestURI();
            method = request.getMethod();
        }
        var request = new RequestLog(requestId, method, path);
        return Flux.defer(() -> {
            var reader = new Reader(actor, session, conversation, cursor, request);
            return Mono.fromCallable(reader::poll).subscribeOn(Schedulers.boundedElastic())
                .repeat().takeUntil(Batch::complete).concatMapIterable(Batch::events);
        });
    }

    private record RequestLog(String id, String method, String path) {
    }

    private record Batch(List<ServerSentEvent<Object>> events, boolean complete) {
    }

    private static ServerSentEvent<Object> control(String type, String reason, StreamCursor cursor) {
        var body = reason == null ? Map.of("lastSequence", cursor.sequence(), "generation", cursor.generation())
            : Map.of("lastSequence", cursor.sequence(), "generation",
            cursor.generation() == null ? "" : cursor.generation(), "reason", reason);
        return ServerSentEvent.builder((Object) body).event(type).build();
    }

    private final class Reader {
        private final AuthContext actor;
        private final String session;
        private final String conversation;
        private final RequestLog request;
        private StreamCursor cursor;
        private long boundary = -1;
        private boolean ready;
        private long checkedAt = clock.millis();
        private long heartbeatAt = clock.millis();

        private Reader(AuthContext actor, String session, String conversation, StreamCursor cursor,
                       RequestLog request) {
            this.actor = actor;
            this.session = session;
            this.conversation = conversation;
            this.cursor = cursor;
            this.request = request;
        }

        private Batch poll() {
            try {
                long now = clock.millis();
                if (now - checkedAt >= 15000) {
                    checkedAt = now;
                    if (!authorization.allowed(session, actor, "conversation.view")) {
                        return new Batch(List.of(), true);
                    }
                    var current = conversations.streamPosition(actor.enterpriseId(), actor.userId(), conversation,
                        clock.instant().minus(Duration.ofDays(7))).orElse(null);
                    if (current == null || current.status().equals("deleted")) {
                        return new Batch(List.of(), true);
                    }
                }
                var position = cache.position(actor.enterpriseId(), conversation).orElse(null);
                if (position == null || !position.cursor().generation().equals(cursor.generation())
                    || Long.parseLong(cursor.sequence()) > Long.parseLong(position.cursor().sequence())) {
                    return reset("sequence_invalid");
                }
                if (boundary < 0) {
                    boundary = Long.parseLong(position.cursor().sequence());
                }
                var output = new ArrayList<ServerSentEvent<Object>>();
                addReady(output);
                var values = cache.after(actor.enterpriseId(), conversation, cursor,
                    position.activeRunId() == null ? Duration.ZERO : Duration.ofSeconds(1), 100);
                for (var event : values) {
                    if (event.protocolVersion() != 2 || Long.parseLong(event.sequence()) != Math.addExact(
                        Long.parseLong(cursor.sequence()), 1)) {
                        return reset("events_expired");
                    }
                    output.add(ServerSentEvent.builder((Object) ToolPayloadPreview.event(event)).id(event.sequence())
                        .event(event.type()).build());
                    cursor = new StreamCursor(event.generation(), event.sequence());
                    addReady(output);
                }
                var latest = cache.position(actor.enterpriseId(), conversation).orElse(null);
                if (latest == null || !latest.cursor().generation().equals(cursor.generation())) {
                    return reset("sequence_invalid");
                }
                if (values.isEmpty() && Long.parseLong(position.cursor().sequence()) > Long.parseLong(
                    cursor.sequence())) {
                    return reset("events_expired");
                }
                if (clock.millis() - heartbeatAt >= 15000) {
                    output.add(ServerSentEvent.builder().comment("保持连接").build());
                    heartbeatAt = clock.millis();
                }
                return new Batch(output, ready && latest.activeRunId() == null
                    && cursor.sequence().equals(latest.cursor().sequence()));
            } catch (RuntimeException failed) {
                LOG.warn("实时连接读取失败，将重新加载完整快照，会话编号 {}，请求编号 {}，请求方法 {}，请求路径 {}",
                    conversation, request.id(), request.method(), request.path(), failed);
                return reset("data_incomplete");
            }
        }

        private void addReady(List<ServerSentEvent<Object>> output) {
            if (!ready && Long.parseLong(cursor.sequence()) >= boundary) {
                output.add(
                    control("stream.ready", null, new StreamCursor(cursor.generation(), Long.toString(boundary))));
                ready = true;
            }
        }

        private Batch reset(String reason) {
            return new Batch(List.of(control("stream.reset", reason, cursor)), true);
        }
    }
}
