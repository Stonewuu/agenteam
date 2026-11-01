package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.provider.wecom.WecomAiBotEventNormalizer;
import com.stonewu.agenteam.service.http.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WecomQuotedMessageTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WecomAiBotEventNormalizer normalizer = new WecomAiBotEventNormalizer(json);
    private final IntegrationApplication app = new IntegrationApplication("connection", "enterprise", "wecom_aibot", null, "bot", 1);
    private final Instant now = Instant.parse("2026-10-30T06:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {"text", "voice"})
    void keepsQuotedWordsSeparateFromTheNewRequest(String type) {
        var frame = message();
        var quote = ((ObjectNode) frame.path("body")).putObject("quote").put("msgtype", type);
        quote.putObject(type).put("content", "预算是3200元，旧任务编号GC-000000000000");
        var event = normalizer.normalize(app, "bot", frame, now);
        assertEquals("引用消息中的预算是多少", event.text());
        assertTrue(event.metadata().path("quotedText").asText().contains("3200元"));
        assertNull(event.replyToMessageId(), "引用正文不能被当成平台原消息编号");
        assertTrue(ChatMessageText.withQuote(event.text(), event.metadata().path("quotedText").asText()).contains("引用消息："));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "file", "video"})
    void quotedResourcesUseTheSameProtectedDownloadFlow(String type) {
        var frame = message();
        var quote = ((ObjectNode) frame.path("body")).putObject("quote").put("msgtype", type);
        quote.putObject(type).put("name", "被引用的材料").put("url", "https://example.test/resource").put("aeskey", "media-key-test");
        var event = normalizer.normalize(app, "bot", frame, now);
        assertEquals(1, event.resources().size());
        assertEquals(type, event.resources().getFirst().type());
        assertEquals("media-key-test", event.resources().getFirst().downloadMaterial().path("aeskey").asText());
        assertFalse(event.metadata().toString().contains("media-key-test"));
        assertFalse(event.metadata().toString().contains("https://example.test/resource"));
        assertFalse(event.metadata().has("quotedText"), "图片或文件引用不能自动追加指导模型的提示词");
    }

    @Test
    void mixedQuoteKeepsTextAndAttachmentsTogether() {
        var frame = message();
        var quote = ((ObjectNode) frame.path("body")).putObject("quote").put("msgtype", "mixed");
        var parts = quote.putObject("mixed").putArray("msg_item");
        parts.addObject().put("msgtype", "text").putObject("text").put("content", "这是预算表");
        parts.addObject().put("msgtype", "image").putObject("image").put("url", "https://example.test/table").put("aeskey", "test-key");
        var event = normalizer.normalize(app, "bot", frame, now);
        assertTrue(event.metadata().path("quotedText").asText().contains("这是预算表"));
        assertEquals(1, event.resources().size());
    }

    @Test
    void oversizedQuoteCannotBypassTheMessageLimit() {
        var frame = message();
        ((ObjectNode) frame.path("body")).putObject("quote").put("msgtype", "text")
            .putObject("text").put("content", "长".repeat(32000));
        assertThrows(ApiException.class, () -> normalizer.normalize(app, "bot", frame, now));
    }

    @Test
    void messagesWithoutQuotesKeepTheirOriginalText() {
        var event = normalizer.normalize(app, "bot", message(), now);
        assertFalse(event.metadata().has("quotedText"));
        assertEquals(event.text(), ChatMessageText.withQuote(json.createObjectNode().put("text", event.text())));
    }

    private ObjectNode message() {
        var frame = json.createObjectNode().put("cmd", "aibot_msg_callback");
        frame.putObject("headers").put("req_id", "request");
        var body = frame.putObject("body").put("msgid", "message").put("aibotid", "bot")
            .put("chattype", "group").put("chatid", "group").put("msgtype", "text");
        body.putObject("from").put("userid", "member");
        body.putObject("text").put("content", "引用消息中的预算是多少");
        return frame;
    }
}
