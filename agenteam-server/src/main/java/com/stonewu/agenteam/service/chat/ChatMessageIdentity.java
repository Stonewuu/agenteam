package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

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
            throw new IllegalArgumentException(Messages.text("error.chat.chatMessageIdentity.canonical.1"));
        }
        String scope = sharedIdentityVerified && app.externalTenantId() != null
            ? "tenant:" + app.externalTenantId() : "bot:" + bindingId;
        return json.hash(json.tree(List.of(app.providerCode(), scope, event.externalChatId(), event.messageId())));
    }

    public String receipt(NormalizedChatEvent event) {
        if (event.deliveryId() == null || event.deliveryId().isBlank()) {
            throw new IllegalArgumentException(Messages.text("error.chat.chatMessageIdentity.receipt.1"));
        }
        return json.hash(json.tree(List.of(event.eventType(), event.deliveryId())));
    }
}
