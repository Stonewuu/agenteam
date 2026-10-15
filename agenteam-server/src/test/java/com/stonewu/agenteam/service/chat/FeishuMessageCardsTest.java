package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuMarkdownParts;
import com.stonewu.agenteam.service.chat.provider.feishu.FeishuMessageCards;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FeishuMessageCardsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void answerUsesMarkdownAndPreservesDestinationWithoutNotifyingMentionedUsers() {
        var source = json.createObjectNode().put("messageType", "text").put("replyInThread", true).put("replyToMessageId", "om_original");
        source.putObject("content").put("text", "**重点**\n- 一项\n`代码`\n<at id=all>所有人</at>");
        var card = FeishuMessageCards.prepare(source);
        assertEquals("interactive", card.path("messageType").asText());
        assertEquals("2.0", card.at("/content/schema").asText());
        assertEquals("**重点**\n- 一项\n`代码`\n@所有人", card.at("/content/body/elements/0/content").asText());
        assertEquals("om_original", card.path("replyToMessageId").asText());
        assertTrue(card.path("replyInThread").asBoolean());
        assertEquals("text", source.path("messageType").asText());
    }

    @Test
    void unverifiedBotHasWorkingWebEntryAndVerifiedBotHasUncheckedDefaultChoice() {
        var source = json.createObjectNode().put("messageType", "text").put("replySettingsUrl", "https://example.com/task?replyMode=edit")
            .put("replyFull", false).put("replyFullAllowed", false).put("replyModeRevision", 3).put("replyDefaultRevision", 7);
        source.putObject("content").put("text", "已收到任务");
        var fallback = FeishuMessageCards.prepare(source).at("/content/body/elements/3/behaviors/0");
        assertEquals("open_url", fallback.path("type").asText());
        assertEquals(source.path("replySettingsUrl").asText(), fallback.path("default_url").asText());
        source.put("replyCallbackEnabled", true).put("replySettingsEditing", true);
        var elements = FeishuMessageCards.prepare(source).at("/content/body/elements/4/elements");
        assertFalse(elements.path(0).path("checked").asBoolean(true));
        assertTrue(elements.path(1).path("disabled").asBoolean());
        assertEquals("form_submit", elements.path(2).path("action_type").asText());
        assertEquals(3, elements.at("/2/behaviors/0/value/expectedRevision").asLong());
        assertEquals(7, elements.at("/2/behaviors/0/value/expectedDefaultRevision").asLong());
    }

    @Test
    void longChineseAndEmojiRemainCompleteWithinByteLimit() {
        String text = "中文🙂一行。\n".repeat(6000);
        var parts = assertTimeout(Duration.ofSeconds(2), () -> FeishuMarkdownParts.split(text));
        assertTrue(parts.size() > 2);
        assertEquals(text, String.join("", parts));
        assertTrue(parts.stream().allMatch(part -> part.getBytes(StandardCharsets.UTF_8).length <= 10000));
        assertFalse(parts.stream().anyMatch(part -> part.contains("�")));
    }

    @Test
    void codeFencesCloseAndReopenAcrossCardsWithoutLosingCode() {
        String body = "const value = '中文';\n".repeat(1500);
        var parts = FeishuMarkdownParts.split("```typescript\n" + body + "```\n");
        assertTrue(parts.stream().allMatch(part -> part.startsWith("```typescript\n") && part.stripTrailing().endsWith("```")));
        assertTrue(parts.stream().allMatch(part -> part.getBytes(StandardCharsets.UTF_8).length <= 10000));
        assertEquals(body.lines().count(), parts.stream().flatMap(String::lines).filter(line -> line.startsWith("const value")).count());
        assertEquals("a".repeat(45000), FeishuMarkdownParts.split("a".repeat(45000)).stream().collect(Collectors.joining()));
    }
}
