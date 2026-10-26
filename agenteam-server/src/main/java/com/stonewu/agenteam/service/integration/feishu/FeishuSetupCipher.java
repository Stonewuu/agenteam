package com.stonewu.agenteam.service.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Component;

/** 注册和管理授权使用产品现有的加密组件，材料与所属记录绑定。 */
@Component
public class FeishuSetupCipher {
    private final PayloadEncryption encryption;
    private final ObjectMapper json;

    public FeishuSetupCipher(PayloadEncryption encryption, ObjectMapper json) {
        this.encryption = encryption;
        this.json = json;
    }

    public String encrypt(String binding, Object value) {
        try {
            return json.writeValueAsString(encryption.encrypt(value, "feishu-setup:" + binding));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("飞书授权材料无法保存", failure);
        }
    }

    public <T> T decrypt(String binding, String value, Class<T> type) {
        try {
            return encryption.decrypt(json.readValue(value, EncryptedPayload.class), "feishu-setup:" + binding, type);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("飞书授权材料无法读取", failure);
        }
    }

    public record Registration(String deviceCode, String url, String appId, String secret) {
        @Override
        public String toString() {
            return "Registration[注册材料已隐藏]";
        }
    }

    public record Tokens(String accessToken, String refreshToken) {
        @Override
        public String toString() {
            return "Tokens[授权凭据已隐藏]";
        }
    }
}
