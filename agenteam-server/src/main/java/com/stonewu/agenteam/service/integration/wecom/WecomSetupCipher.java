package com.stonewu.agenteam.service.integration.wecom;

import com.stonewu.agenteam.service.i18n.Messages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.WecomSetupJobRow;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.integration.provider.wecom.WecomQuickCreateClient.Registration;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Component;

/** 创建凭据与企业及任务绑定，不向浏览器或日志返回。 */
@Component
public class WecomSetupCipher {
    private final ObjectMapper json;
    private final PayloadEncryption encryption;

    public WecomSetupCipher(ObjectMapper json, PayloadEncryption encryption) {
        this.json = json;
        this.encryption = encryption;
    }

    public String encrypt(WecomSetupJobRow row, Registration value) {
        try {
            return json.writeValueAsString(encryption.encrypt(value, binding(row)));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(Messages.text("error.integration.wecomSetupCipher.encrypt.1"), failure);
        }
    }

    public Registration read(WecomSetupJobRow row) {
        try {
            return encryption.decrypt(json.readValue(row.getEncryptedJson(), EncryptedPayload.class), binding(row), Registration.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(Messages.text("error.integration.wecomSetupCipher.read.1"), failure);
        }
    }

    private String binding(WecomSetupJobRow row) {
        return "wecom-setup:" + row.getEnterpriseId() + ":" + row.getId();
    }
}
