package com.stonewu.agenteam.service.execution;

import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.response.StreamCursor;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.i18n.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 所有会话统一读取 Redis 近期事件，旧客户端先重新加载完整快照。
 */
@Service
public class ConversationEventService {
    private final ConversationQueryService queries;
    private final LiveConversationEventService live;

    public ConversationEventService(ConversationQueryService queries, LiveConversationEventService live) {
        this.queries = queries;
        this.live = live;
    }

    public Flux<ServerSentEvent<Object>> connect(AuthContext actor, String sessionId, String conversation, String after,
                                                 String lastEventId) {
        return connect(actor, sessionId, conversation, after, lastEventId, null);
    }

    public Flux<ServerSentEvent<Object>> connect(AuthContext actor, String sessionId, String conversation, String after,
                                                 String lastEventId, String generation) {
        long position = parseAfter(after, lastEventId);
        queries.readable(actor, conversation);
        return live.connect(actor, sessionId, conversation, new StreamCursor(generation, Long.toString(position)));
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
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            Messages.text("error.execution.conversationEventService.invalidAfter.1"));
    }
}
