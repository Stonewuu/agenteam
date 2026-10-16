package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.service.collaboration.CollaborationDispatchService;
import com.stonewu.agenteam.service.integration.IntegrationSecretService;
import com.stonewu.agenteam.support.InvitationHttpTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 从公开 HTTP 回调进入正式接收事务；没有网页会话仍必须通过平台签名及密文验证。 */
class ChatWebhookTest extends CollaborationApiTestSupport {
    private static final InvitationHttpTestEnvironment ENV = new InvitationHttpTestEnvironment();
    private static final String KEY = "isolated-feishu-encrypt-key";
    private static final String TOKEN = "isolated-feishu-verification-token";
    @Autowired IntegrationSecretService secrets;
    @Autowired PlatformTransactionManager manager;
    @Autowired CollaborationDispatchService dispatch;
    private String callback;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        ENV.properties(registry);
        registry.add("chat.enabled", () -> true);
        registry.add("chat.feishu.enabled", () -> true);
        registry.add("chat.worker-enabled", () -> false);
    }

    @BeforeEach
    void callbackFixture() {
        String key = UUID.randomUUID().toString().replace("-", "");
        callback = "/api/v1/chat/callbacks/feishu/" + key;
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            secrets.store(enterprise, bot.getConnectionId(), "chat_verification_token", TOKEN, admin, 1, Instant.now());
            secrets.store(enterprise, bot.getConnectionId(), "chat_encrypt_key", KEY, admin, 1, Instant.now());
            databaseAccess.mapper(ChatBotBindingMapper.class).update(new LambdaUpdateWrapper<ChatBotBindingRow>()
                .eq(ChatBotBindingRow::getId, bot.getId()).set(ChatBotBindingRow::getTransport, "webhook").set(ChatBotBindingRow::getPublicCallbackKey, key));
        });
    }

    @AfterEach
    void stopRuns() {
        lifecycle.stopForUser(enterprise, initiator.id());
    }

    @AfterAll
    void closeEnvironment() throws Exception {
        ENV.close();
    }

    @Test
    void callbackChallengeAndAuthenticationAllowOnlyOneRetriedBusinessRequest() throws Exception {
        mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(encrypt(json.valueToTree(Map.of(
            "type", "url_verification", "token", TOKEN, "challenge", "isolated-challenge")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value("isolated-challenge"));
        mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(encrypt(json.valueToTree(Map.of(
            "type", "url_verification", "token", "wrong-token", "challenge", "isolated-challenge")))))
            .andExpect(status().is4xxClientError());
        assertEquals(0, receiptCount());
        {
            var event = message("forged", "forged", "user", "伪造平台请求");
            event.withObject("/header").put("token", TOKEN);
            byte[] raw = encrypt(event);
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().is4xxClientError());
            mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("X-Lark-Request-Timestamp", timestamp).header("X-Lark-Request-Nonce", "test-nonce")
                .header("X-Lark-Signature", "0".repeat(64))).andExpect(status().is4xxClientError());
            String expired = Long.toString(Instant.now().minusSeconds(400).getEpochSecond());
            mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("X-Lark-Request-Timestamp", expired).header("X-Lark-Request-Nonce", "test-nonce")
                .header("X-Lark-Signature", sign(raw, expired))).andExpect(status().is4xxClientError());
            assertEquals(0, receiptCount());
            assertEquals(0, taskCount());
        }
        var event = message("webhook-retry", "webhook-message", "user", "统计测试考勤");
        event.withObject("/header").put("token", TOKEN);
        byte[] raw = encrypt(event);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        for (int index = 0; index < 3; index++) {
            mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(raw)
                .header("X-Lark-Request-Timestamp", timestamp).header("X-Lark-Request-Nonce", "test-nonce")
                .header("X-Lark-Signature", sign(raw, timestamp))).andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("success"));
        }
        normalizeAll();
        routing.route(enterprise, onlyMessage().getId());
        var lease = dispatch.claim("webhook-dispatch").orElseThrow();
        assertNotNull(dispatch.submit(lease));
        assertNull(dispatch.submit(lease));
        assertEquals(1, receiptCount());
        assertEquals(1, messageCount());
        assertEquals(1, taskCount());
        assertEquals(1, onlyTask().getRunCount());
    }

    @Test
    void signedCardActionReturnsNativeOperatorFeedbackWithoutCreatingTask() throws Exception {
        var event = json.createObjectNode();
        event.putObject("header").put("token", TOKEN).put("app_id", bots.application(bot).externalAppId())
            .put("event_type", "card.action.trigger").put("event_id", "missing-card");
        event.putObject("event").putObject("context").put("open_message_id", "om_missing").put("open_chat_id", externalChat);
        byte[] raw = encrypt(event);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        mvc.perform(post(callback).contentType(MediaType.APPLICATION_JSON).content(raw)
            .header("X-Lark-Request-Timestamp", timestamp).header("X-Lark-Request-Nonce", "test-nonce")
            .header("X-Lark-Signature", sign(raw, timestamp))).andExpect(status().isOk())
            .andExpect(jsonPath("$.toast.type").value("warning")).andExpect(jsonPath("$.toast.content").isString())
            .andExpect(jsonPath("$.nodeType").doesNotExist());
        assertEquals(0, taskCount());
        assertEquals(0, messageCount());
    }

    private byte[] encrypt(JsonNode plain) throws Exception {
        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) 9);
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.UTF_8)), "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));
        byte[] combined = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return json.writeValueAsBytes(Map.of("encrypt", Base64.getEncoder().encodeToString(combined)));
    }

    private String sign(byte[] raw, String timestamp) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update((timestamp + "test-nonce" + KEY).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(raw));
    }
}
