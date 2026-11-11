package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.databind.JsonNode;

/** 引用内容参与阅读和展示；路由与操作指令仍只读取本次正文。 */
public final class ChatMessageText {
    private ChatMessageText() {
    }

    public static String withQuote(JsonNode content) {
        return withQuote(content.path("text").asText(), content.path("quotedText").asText());
    }

    public static String withQuote(String text, String quote) {
        return quote.isBlank() ? text : Messages.text("text.chat.chatMessageText.withQuote.1", text, quote);
    }
}
