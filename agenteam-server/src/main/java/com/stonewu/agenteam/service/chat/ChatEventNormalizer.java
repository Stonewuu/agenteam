package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;

import java.time.Instant;

/** 规范化入口只接收已经认证的连接或回调，不承担用户身份授权。 */
public interface ChatEventNormalizer {
    String providerCode();

    NormalizedChatEvent normalize(IntegrationApplication application, String ownBotId, JsonNode envelope,
                                  Instant receivedAt);
}
