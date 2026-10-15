package com.stonewu.agenteam.controller.chat;

import com.stonewu.agenteam.service.chat.ChatWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 对平台返回原生确认协议，业务处理发生在保存接收记录之后。 */
@RestController
public class ChatWebhookController {
    private final ChatWebhookService callbacks;
    public ChatWebhookController(ChatWebhookService callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping("/api/v1/chat/callbacks/feishu/{key}")
    public Map<String, Object> accept(@PathVariable String key, @RequestBody byte[] body, HttpServletRequest request) {
        return callbacks.accept(key, body, request.getHeader("X-Lark-Request-Timestamp"),
            request.getHeader("X-Lark-Request-Nonce"), request.getHeader("X-Lark-Signature"));
    }
}
