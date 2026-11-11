package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.DateTimeException;

/** 只接受完整消息，不将不完整或错误的载荷转换成可执行指令。 */
public final class ChatInputValidation {
    private static final Logger LOG = LoggerFactory.getLogger(ChatInputValidation.class);

    private ChatInputValidation() {
    }

    public static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank() || value.length() > 191 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidField("message", Messages.text("error.chat.chatInputValidation.required.1"));
        }
        return value;
    }

    public static String optional(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value != null && (value.length() > 191 || value.codePoints().anyMatch(Character::isISOControl))) {
            throw ApiException.invalidField("message", Messages.text("error.chat.chatInputValidation.optional.1"));
        }
        return value == null || value.isBlank() ? null : value;
    }

    public static JsonNode content(ObjectMapper mapper, String value, String connection, String message) {
        try {
            var result = mapper.readTree(value);
            if (result == null || !result.isObject()) {
                throw ApiException.invalidField("message", Messages.text("error.chat.chatInputValidation.content.1"));
            }
            return result;
        } catch (JsonProcessingException failure) {
            var safe = new ChannelProviderException("CHAT_PAYLOAD_INVALID", Messages.text("error.chat.chatInputValidation.content.2"), failure);
            LOG.warn(Messages.log("log.chat.chatInputValidation.content.1"), connection, message, safe);
            throw safe;
        }
    }

    public static Instant time(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long numeric = Long.parseLong(value);
            if (numeric < 0) {
                throw new DateTimeException(Messages.text("error.chat.chatInputValidation.time.1"));
            }
            var result = numeric < 100_000_000_000L ? Instant.ofEpochSecond(numeric) : Instant.ofEpochMilli(numeric);
            if (result.isAfter(Instant.parse("9999-12-31T23:59:59Z"))) {
                throw new DateTimeException(Messages.text("error.chat.chatInputValidation.time.2"));
            }
            return result;
        } catch (NumberFormatException | DateTimeException failure) {
            var safe = new ChannelProviderException("CHAT_TIME_INVALID", Messages.text("error.chat.chatInputValidation.time.3"), failure);
            LOG.warn(Messages.log("log.chat.chatInputValidation.time.1"), safe);
            throw ApiException.invalidField("message", Messages.text("error.chat.chatInputValidation.time.3"));
        }
    }

    public static void size(String text, int resources) {
        ResourceInput.text(text, "message", 32000, false);
        if (text.codePointCount(0, text.length()) > 32000 || resources > 20) {
            throw ApiException.invalidField("message", Messages.text("error.chat.chatInputValidation.size.1"));
        }
    }
}
