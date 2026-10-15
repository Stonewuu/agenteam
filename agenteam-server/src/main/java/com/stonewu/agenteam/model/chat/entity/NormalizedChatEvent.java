package com.stonewu.agenteam.model.chat.entity;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/** 已由平台适配器解释的事件；只在内部传递，不作为公网请求。 */
public record NormalizedChatEvent(String providerCode, String eventType, Kind kind, String deliveryId,
                                  String messageId, String externalChatId, String chatKind,
                                  String senderKind, String subjectType, String senderId,
                                  String text, String messageType, List<Mention> mentions,
                                  List<Resource> resources, String replyToMessageId, String threadId,
                                  Instant platformCreatedAt, JsonNode metadata) {
    public NormalizedChatEvent {
        mentions = List.copyOf(mentions);
        resources = List.copyOf(resources);
    }

    public enum Kind {
        MESSAGE, BOT_JOINED, BOT_LEFT, CHAT_UPDATED, MESSAGE_RECALLED, FEEDBACK, SESSION_ENTERED,
        CARD_ACTION, CONNECTION_REPLACED, IGNORED
    }

    public record Mention(String externalId, String name, String kind, String placeholder) {
        public Mention(String externalId, String name, String kind) {
            this(externalId, name, kind, null);
        }
    }

    /** 平台下载材料只加密保存，不能返回到用户接口或模型上下文。 */
    public record Resource(String key, String name, String type, JsonNode downloadMaterial) {
    }

    public boolean humanMessage() {
        return kind == Kind.MESSAGE && "user".equals(senderKind);
    }
}
