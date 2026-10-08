package com.stonewu.agenteam.controller.execution;

import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.execution.ConversationEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * SSE（服务端持续推送事件）连接只负责订阅，断开页面不会取消后台任务。
 */
@RestController
@RequestMapping("/api/v1/enterprises/{enterpriseId}/conversations/{conversationId}/events")
public class ConversationEventController {

    private final AuthContextService identity;

    private final ConversationEventService events;

    public ConversationEventController(AuthContextService identity, ConversationEventService events) {
        this.identity = identity;
        this.events = events;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> connect(@PathVariable String enterpriseId,
                                                                 @PathVariable String conversationId,
                                                                 @RequestParam(required = false) String after,
                                                                 @RequestParam(required = false) String generation,
                                                                 @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
                                                                 HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        var stream = events.connect(actor, request.getSession(false).getId(), conversationId, after, lastEventId,
            generation);
        return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform").header("X-Accel-Buffering", "no")
            .contentType(MediaType.TEXT_EVENT_STREAM).body(stream);
    }
}
