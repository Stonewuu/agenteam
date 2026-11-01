package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelSendResult.Outcome;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.service.chat.provider.wecom.WecomAiBotChatAdapter;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import com.stonewu.agenteam.service.integration.WecomResourceDownloader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 按企微官方三步上传协议核对实际分片字节、编号、授权和主动消息回执。 */
class WecomMediaProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final IntegrationApplication application = new IntegrationApplication("test-app", "test-enterprise", "wecom_aibot", null, "test-bot", 1);
    private final List<Request> requests = new ArrayList<>();
    private final AtomicInteger leaseChecks = new AtomicInteger();
    private final AtomicInteger businessChecks = new AtomicInteger();
    private WecomAiBotChatAdapter adapter;
    private String failedCommand;
    private int errorCode;
    private int revokeAfter = Integer.MAX_VALUE;

    @BeforeEach
    void authenticatedConnection() {
        var connection = mock(ChatConnectionProvider.Connection.class);
        when(connection.ready()).thenReturn(true);
        when(connection.request(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            var request = new Request(invocation.getArgument(0), invocation.getArgument(1), invocation.<JsonNode>getArgument(2).deepCopy());
            requests.add(request);
            var response = json.createObjectNode().put("errcode", request.command().equals(failedCommand) ? errorCode : 0);
            response.putObject("headers").put("req_id", request.id());
            response.putObject("body").put("upload_id", "test-upload").put("media_id", "test-media").put("msgid", "test-message");
            return response;
        });
        var registry = new ChatConnectionRegistry();
        registry.put(new ChatConnectionRegistry.Owned(application, "binding", 1, connection, () -> {
            if (leaseChecks.incrementAndGet() > revokeAfter) {
                throw new ChannelProviderException("CHAT_LEASE_LOST", "本次测试连接租约已失效。");
            }
        }));
        adapter = new WecomAiBotChatAdapter(registry, mock(IntegrationQueryService.class), json, mock(WecomResourceDownloader.class));
    }

    @Test
    void existingReplySettingsCardsKeepTheirOriginalWireFormat() {
        var payload = json.createObjectNode().put("receiveId", "member").put("chatType", 1)
            .put("requestId", "notice-request").put("messageType", "text")
            .put("replySettingsUrl", "https://example.test/task?replyMode=edit")
            .put("replyFull", true).put("replyNoticeTitle", "已收到你的消息");
        payload.putObject("content").put("text", "处理完成后会在此回复。");
        assertEquals(Outcome.ACCEPTED, adapter.send(application, payload, businessChecks::incrementAndGet).result().outcome());
        var request = requests.getLast();
        assertEquals("aibot_send_msg", request.command());
        assertEquals("notice-request", request.id());
        assertEquals(1, request.body().path("chat_type").asInt());
        assertEquals("template_card", request.body().path("msgtype").asText());
        assertEquals("直接回复内容", request.body().at("/template_card/horizontal_content_list/0/value").asText());
        assertEquals("https://example.test/task?replyMode=edit", request.body().at("/template_card/jump_list/0/url").asText());
        assertFalse(request.body().has("markdown"));
        assertEquals(1, businessChecks.get());
        assertEquals(1, leaseChecks.get());

        payload.put("requestId", "conversation-card").put("conversationCardId", "outbound-card-id");
        adapter.send(application, payload, businessChecks::incrementAndGet);
        var card = requests.getLast().body().path("template_card");
        assertEquals("button_interaction", card.path("card_type").asText());
        assertEquals("outbound-card-id", card.path("task_id").asText());
        assertEquals("conversation_new", card.at("/button_list/0/key").asText());
        assertEquals("conversation_resume", card.at("/button_list/1/key").asText());

        String answer = "## 完整答案\n" + "这是需要保留的中文长回答。".repeat(120);
        payload.remove("replySettingsUrl");
        payload.put("requestId", "answer-request");
        payload.putObject("content").put("text", answer);
        adapter.send(application, payload, businessChecks::incrementAndGet);
        assertEquals(answer, requests.getLast().body().at("/markdown/content").asText());
        assertFalse(requests.getLast().body().has("template_card"));
    }

    @Test
    void alreadySubmittedAnswerCardsKeepTheirWireFormat() {
        String answer = "完整答案\n" + "这是应保留的长回答。".repeat(150) + "\n最后一段🙂";
        var payload = json.createObjectNode().put("receiveId", "group").put("chatType", 2)
            .put("requestId", "answer-card").put("messageType", "text").put("replyFull", true)
            .put("conversationCardId", "answer-outbound").put("replyResultUrl", "https://example.test/task")
            .put("replyNoticeTitle", "回复");
        payload.putObject("content").put("text", answer);
        assertEquals(Outcome.ACCEPTED, adapter.send(application, payload, businessChecks::incrementAndGet).result().outcome());
        var body = requests.getLast().body();
        assertEquals("template_card", body.path("msgtype").asText());
        var card = body.path("template_card");
        assertEquals(answer, card.path("sub_title_text").asText());
        assertEquals("answer-outbound", card.path("task_id").asText());
        assertEquals("新会话", card.at("/button_list/0/text").asText());
        assertEquals("继续该会话", card.at("/button_list/1/text").asText());
        assertEquals("conversation_new", card.at("/button_list/0/key").asText());
        assertEquals("conversation_resume", card.at("/button_list/1/key").asText());
        assertEquals("https://example.test/task", card.at("/card_action/url").asText());
        assertFalse(card.has("horizontal_content_list"));
        assertFalse(card.has("jump_list"));
        assertFalse(body.has("markdown"));
    }

    @Test
    void historyCardContainsOnlyLabelsAndScopedChoiceKeysWithAtMostSixButtons() {
        var payload = json.createObjectNode().put("receiveId", "group").put("chatType", 2)
            .put("requestId", "history-menu").put("messageType", "text").put("conversationCardId", "menu-outbound")
            .put("historyUserId", "private-member").put("historyPage", 2).put("historyHasNext", true);
        payload.putObject("content").put("text", "请选择要继续的会话。");
        var choices = payload.putArray("historyChoices");
        for (int index = 0; index < 4; index++) {
            choices.addObject().put("key", "history_choose_" + index).put("taskId", "private-task-" + index)
                .put("label", "会话" + index + " · 5分钟前");
        }
        assertEquals(Outcome.ACCEPTED, adapter.send(application, payload, businessChecks::incrementAndGet).result().outcome());
        var body = requests.getLast().body();
        var card = body.path("template_card");
        assertEquals("button_interaction", card.path("card_type").asText());
        assertEquals("menu-outbound", card.path("task_id").asText());
        assertEquals(6, card.path("button_list").size());
        assertEquals("history_previous", card.at("/button_list/4/key").asText());
        assertEquals("history_next", card.at("/button_list/5/key").asText());
        assertFalse(body.toString().contains("private-member"));
        assertFalse(body.toString().contains("private-task"));
    }

    @Test
    void historyTitlesAreInTheBodyAndButtonsStayShort() {
        var payload = json.createObjectNode().put("receiveId", "group").put("messageType", "text")
            .put("requestId", "history-list").put("conversationCardId", "menu")
            .put("historyListLayout", true).put("historyPage", 2).put("historyHasNext", true);
        payload.putObject("content").put("text", "请选择要继续的会话。");
        var choices = payload.putArray("historyChoices");
        for (int index = 0; index < 3; index++) {
            choices.addObject().put("key", "history_choose_" + index).put("taskId", "task-" + index)
                .put("title", "请详细介绍这一项工作需要哪些具体能力" + index).put("label", "继续 " + (index + 1));
        }
        adapter.send(application, payload, businessChecks::incrementAndGet);
        var card = requests.getLast().body().path("template_card");
        assertEquals(3, card.path("sub_title_text").asText().lines().count());
        for (int index = 0; index < 3; index++) {
            assertTrue(card.path("sub_title_text").asText().contains(choices.get(index).path("title").asText()));
            assertEquals("继续 " + (index + 1), card.path("button_list").get(index).path("text").asText());
        }
        assertEquals(5, card.path("button_list").size());
    }

    @Test
    void uploadUsesZeroBasedChunksAndReconstructsEveryByteWithTheDeclaredDigest() throws Exception {
        byte[] content = new byte[524288 + 137];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index % 251);
        }
        assertEquals("test-media", adapter.upload(application, file(content), businessChecks::incrementAndGet));
        assertEquals(List.of("aibot_upload_media_init", "aibot_upload_media_chunk", "aibot_upload_media_chunk", "aibot_upload_media_finish"),
            requests.stream().map(Request::command).toList());
        assertEquals(List.of(0, 1), requests.stream().filter(request -> request.command().equals("aibot_upload_media_chunk"))
            .map(request -> request.body().path("chunk_index").asInt()).toList());
        var initial = requests.getFirst().body();
        assertEquals(content.length, initial.path("total_size").asInt());
        assertEquals(2, initial.path("total_chunks").asInt());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content)), initial.path("md5").asText());
        var restored = new ByteArrayOutputStream();
        for (var request : requests) {
            if (request.command().equals("aibot_upload_media_chunk")) {
                assertEquals("test-upload", request.body().path("upload_id").asText());
                restored.writeBytes(Base64.getDecoder().decode(request.body().path("base64_data").asText()));
            }
        }
        assertArrayEquals(content, restored.toByteArray());
        assertEquals(4, businessChecks.get());
        assertEquals(4, leaseChecks.get());
        assertEquals(4, requests.stream().map(Request::id).distinct().count());
    }

    @Test
    void rejectedChunkDoesNotFinishTheUploadOrSendAFileMessage() {
        failedCommand = "aibot_upload_media_chunk";
        errorCode = 45009;
        assertEquals("CHAT_FILE_UPLOAD_REJECTED", assertThrows(ChannelProviderException.class,
            () -> adapter.upload(application, file(new byte[]{1, 2, 3}), businessChecks::incrementAndGet)).code());
        assertEquals(2, requests.size());
        assertEquals("aibot_upload_media_chunk", requests.getLast().command());
    }

    @Test
    void losingTheLeaseAfterInitializationStopsBeforeSendingTheNextChunk() {
        revokeAfter = 1;
        assertEquals("CHAT_LEASE_LOST", assertThrows(ChannelProviderException.class,
            () -> adapter.upload(application, file(new byte[]{1, 2, 3}), businessChecks::incrementAndGet)).code());
        assertEquals(1, requests.size());
        assertEquals(1, businessChecks.get());
    }

    @ParameterizedTest
    @CsvSource({"text,markdown", "file,file"})
    void sendingUsesThePreparedBotRequestAndMessageType(String type, String platformType) {
        var payload = json.createObjectNode().put("messageType", type).put("receiveId", "test-chat").put("requestId", "fixed-request");
        payload.putObject("content").put("text", "合成文字").put("media_id", "test-media");
        var sent = adapter.send(application, payload, businessChecks::incrementAndGet);
        assertEquals(Outcome.ACCEPTED, sent.result().outcome());
        assertEquals("test-message", sent.result().messageId());
        assertEquals("fixed-request", requests.getFirst().id());
        assertEquals("aibot_send_msg", requests.getFirst().command());
        assertEquals("test-chat", requests.getFirst().body().path("chatid").asText());
        assertEquals(platformType, requests.getFirst().body().path("msgtype").asText());
        assertEquals(type.equals("file") ? "test-media" : "合成文字", requests.getFirst().body()
            .at(type.equals("file") ? "/file/media_id" : "/markdown/content").asText());
        assertEquals(1, businessChecks.get());
        assertEquals(1, leaseChecks.get());
    }

    @ParameterizedTest
    @CsvSource({"45009,RETRYABLE_FAILURE", "40014,PERMANENT_FAILURE"})
    void platformFailureIsReturnedWithoutRetryingInsideTheAdapter(int code, Outcome outcome) {
        failedCommand = "aibot_send_msg";
        errorCode = code;
        var payload = json.createObjectNode().put("receiveId", "test-chat").put("requestId", "fixed-failed-request");
        payload.putObject("content").put("text", "合成文字");
        assertEquals(outcome, adapter.send(application, payload, businessChecks::incrementAndGet).result().outcome());
        assertEquals(1, requests.size());
    }

    @Test
    void anotherBotOrCredentialRevisionCannotReuseThisConnection() {
        var other = new IntegrationApplication(application.id(), application.enterpriseId(), "wecom_aibot", null, "other-bot", 2);
        assertEquals("CHAT_CONNECTION_UNAVAILABLE", assertThrows(ChannelProviderException.class,
            () -> adapter.upload(other, file(new byte[]{1}), businessChecks::incrementAndGet)).code());
        assertTrue(requests.isEmpty());
    }

    private ChatPlatformAdapter.UploadFile file(byte[] bytes) {
        return new ChatPlatformAdapter.UploadFile("合成考勤.csv", "text/csv", bytes.length, () -> new ByteArrayInputStream(bytes));
    }

    private record Request(String id, String command, JsonNode body) {
    }
}
