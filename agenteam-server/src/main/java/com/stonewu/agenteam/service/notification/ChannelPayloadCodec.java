package com.stonewu.agenteam.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** 固定键顺序后计算摘要，避免 MySQL 保存 JSON 时调整键顺序导致误判。 */
@Component
public class ChannelPayloadCodec {
    private final ObjectMapper json;

    public ChannelPayloadCodec(ObjectMapper json) {
        this.json = json;
    }

    public String encode(JsonNode value) {
        try {
            return json.writeValueAsString(ordered(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("通知请求内容无法保存", failure);
        }
    }

    public String hash(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("通知请求内容无法校验", failure);
        }
    }

    public JsonNode decode(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("已保存的通知请求无法读取", failure);
        }
    }

    private JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            var keys = new TreeSet<String>();
            value.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                result.set(key, ordered(value.get(key)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(item -> result.add(ordered(item)));
            return result;
        }
        return value;
    }
}
