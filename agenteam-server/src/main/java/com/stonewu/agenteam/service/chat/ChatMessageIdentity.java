package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import org.springframework.stereotype.Component;

import java.util.List;

/** 根据平台保证的身份范围去重，不比较正文或本机接收时间。 */
@Component
public class ChatMessageIdentity {
    private final ResourceJson json;

    public ChatMessageIdentity(ResourceJson json) {
        this.json = json;
    }

    public String canonical(IntegrationApplication app, String bindingId, NormalizedChatEvent event,
                            boolean sharedIdentityVerified) {
        if (event.messageId() == null || event.externalChatId() == null) {
            throw new IllegalArgumentException("普通消息必须包含真实消息编号和会话编号");
        }
        String scope = sharedIdentityVerified && app.externalTenantId() != null
            ? "tenant:" + app.externalTenantId() : "bot:" + bindingId;
        return json.hash(json.tree(List.of(app.providerCode(), scope, event.externalChatId(), event.messageId())));
    }

    public String receipt(NormalizedChatEvent event) {
        if (event.deliveryId() == null || event.deliveryId().isBlank()) {
            throw new IllegalArgumentException("需要保存的事件缺少稳定的投递编号");
        }
        return json.hash(json.tree(List.of(event.eventType(), event.deliveryId())));
    }
}
