package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.List;

/** 只移除平台已经确认的开头提及，避免“@机器人 新任务”被当成继续旧任务。 */
public final class ChatCommandText {
    private ChatCommandText() {
    }

    public static String display(String text, JsonNode mentions) {
        String result = text;
        for (var mention : mentions) {
            String placeholder = mention.path("placeholder").asText("");
            String name = mention.path("name").asText("");
            if (!placeholder.isBlank() && !name.isBlank()) {
                result = result.replaceAll(Pattern.quote(placeholder) + "(?![A-Za-z0-9_])", Matcher.quoteReplacement("@" + name));
            }
        }
        return result;
    }

    public static boolean newTask(String text, JsonNode mentions) {
        return withoutLeadingMentions(text, mentions).startsWith("新任务");
    }

    public static String withoutLeadingMentions(String text, JsonNode mentions) {
        String value = text.stripLeading();
        for (int index = 0; index < mentions.size(); index++) {
            boolean removed = false;
            for (var mention : mentions) {
                String placeholder = mention.path("placeholder").asText("");
                String name = mention.path("name").asText("");
                for (String candidate : List.of(placeholder, name.isBlank() ? "" : "@" + name)) {
                    if (!candidate.isBlank() && value.startsWith(candidate)
                        && (value.length() == candidate.length() || Character.isWhitespace(value.charAt(candidate.length())))) {
                        value = value.substring(candidate.length()).stripLeading();
                        removed = true;
                        break;
                    }
                }
                if (removed) {
                    break;
                }
            }
            if (!removed) {
                break;
            }
        }
        return value;
    }
}
