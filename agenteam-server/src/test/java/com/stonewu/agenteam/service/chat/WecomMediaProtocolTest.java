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
