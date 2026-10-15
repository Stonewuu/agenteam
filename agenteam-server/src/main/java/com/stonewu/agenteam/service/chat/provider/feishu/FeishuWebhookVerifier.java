package com.stonewu.agenteam.service.chat.provider.feishu;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.EventDispatcher;
import com.stonewu.agenteam.service.chat.ChatSocket;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/** 固定原始请求验签，再用官方开发包解密；回调认证不依赖网页会话。 */
@Component
public class FeishuWebhookVerifier {
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    public FeishuWebhookVerifier(Clock clock) {
        this.clock = clock;
    }

    public JsonNode verify(byte[] raw, String timestamp, String nonce, String signature, String token, String key) {
        if (raw.length > 1_048_576 || token == null || token.isBlank() || key == null || key.isBlank()) {
            throw rejected();
        }
        try {
            var encrypted = json.readTree(raw);
            if (!encrypted.path("encrypt").isTextual()) {
                throw rejected();
            }
            var plain = json.readTree(new EventDispatcher.Builder(token, key).build().decryptEvent(encrypted.path("encrypt").asText()));
            String supplied = plain.path("header").path("token").asText(plain.path("token").asText());
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                throw rejected();
            }
            if (plain.path("type").asText().equals("url_verification")) {
                if (!plain.path("challenge").isTextual() || plain.path("challenge").asText().length() > 1024) {
                    throw rejected();
                }
                return plain;
            }
            if (timestamp == null || !timestamp.matches("[0-9]{10,11}") || nonce == null || !nonce.matches("[A-Za-z0-9_-]{1,256}")
                || signature == null || !signature.matches("[a-fA-F0-9]{64}")
                || Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > 300) {
                throw rejected();
            }
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((timestamp + nonce + key).getBytes(StandardCharsets.UTF_8));
            byte[] expected = digest.digest(raw);
            if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) {
                throw rejected();
            }
            return plain;
        } catch (ApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHAT_CALLBACK_INVALID", "平台回调认证未通过。",
                ChatSocket.safe("CHAT_CALLBACK_INVALID", "平台回调格式或加密验证失败。", failure));
        }
    }

    private ApiException rejected() {
        return new ApiException(HttpStatus.FORBIDDEN, "CHAT_CALLBACK_INVALID", "平台回调认证未通过。");
    }
}
