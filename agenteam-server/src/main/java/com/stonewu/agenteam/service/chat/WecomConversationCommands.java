package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/** 只识别开头独立的会话命令，正文、引用和相似单词不触发切换。 */
public final class WecomConversationCommands {
    private static final Pattern NEW = Pattern.compile("^/new(?:\\s+(.*))?$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);

    private WecomConversationCommands() {
    }

    public static Parsed parse(String text, JsonNode mentions) {
        return parse(text, mentions, null, null);
    }

    public static Parsed parse(String text, JsonNode mentions, String botId, String botName) {
        String value = ChatCommandText.withoutLeadingMentions(text, mentions).strip();
        boolean mentioned = false;
        for (var mention : mentions) {
            mentioned |= botId != null && botId.equals(mention.path("externalId").asText())
                && "bot".equals(mention.path("kind").asText());
        }
        if (mentioned && botName != null && !botName.isBlank()) {
            value = withoutOwnMention(value, botName);
        }
        if ("/history".equalsIgnoreCase(value)) {
            return new Parsed(false, true, "");
        }
        var match = NEW.matcher(value);
        if (match.matches()) {
            return new Parsed(true, false, match.group(1) == null ? "" : match.group(1).strip());
        }
        return new Parsed(false, false, text);
    }

    public static String withoutOwnMention(String text, String botName) {
        String value = text.strip();
        if (botName == null || botName.isBlank()) {
            return value;
        }
        String mention = "@" + botName;
        if (value.startsWith(mention) && (value.length() == mention.length()
                || Character.isWhitespace(value.charAt(mention.length())) || value.charAt(mention.length()) == '/')) {
            value = value.substring(mention.length()).strip();
        }
        if (value.endsWith(mention)) {
            value = value.substring(0, value.length() - mention.length()).strip();
        }
        return value;
    }

    public record Parsed(boolean newConversation, boolean history, String input) {
    }
}
