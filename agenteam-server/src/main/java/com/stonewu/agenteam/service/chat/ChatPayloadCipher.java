package com.stonewu.agenteam.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Component;

/** 消息及交接内容绑定企业、用途和记录编号，不能移到另一条记录使用。 */
@Component
public class ChatPayloadCipher {
    private final PayloadEncryption encryption;
    private final ObjectMapper mapper;
    private final ResourceJson json;

    public ChatPayloadCipher(PayloadEncryption encryption, ObjectMapper mapper, ResourceJson json) {
        this.encryption = encryption;
        this.mapper = mapper;
        this.json = json;
    }

    public String encrypt(String enterprise, String purpose, String id, Object value) {
        return json.write(json.tree(encryption.encrypt(value, binding(enterprise, purpose, id))));
    }

    public JsonNode decrypt(String enterprise, String purpose, String id, String value) {
        var stored = mapper.convertValue(json.read(value), EncryptedPayload.class);
        return encryption.decrypt(stored, binding(enterprise, purpose, id), JsonNode.class);
    }

    private String binding(String enterprise, String purpose, String id) {
        return "collaboration:" + purpose + ":" + enterprise + ":" + id + ":1";
    }
}
