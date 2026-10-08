package com.stonewu.agenteam.mapper.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/**
 * 对象字段排序后生成摘要，数组保留业务顺序；配置正文另有字节上限。
 */
@Component
public class ResourceJson {
    private final ObjectMapper json;

    public ResourceJson(ObjectMapper json) {
        this.json = json;
    }

    public String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("资源配置无法保存", exception);
        }
    }

    public JsonNode read(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已保存的资源配置无法读取", exception);
        }
    }

    public JsonNode tree(Object value) {
        return json.valueToTree(value);
    }

    public Map<String, Object> object(JsonNode value) {
        return json.convertValue(value, new TypeReference<>() {
        });
    }

    public String hash(JsonNode value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("资源配置摘要无法计算", exception);
        }
    }

    public JsonNode checkedSize(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw ApiException.invalidField("config", "请填写资源配置。");
        }
        try {
            if (json.writeValueAsBytes(value).length > 256 * 1024) {
                throw ApiException.invalidField("config", "配置内容不能超过 262144 字节。");
            }
        } catch (JsonProcessingException exception) {
            throw ApiException.invalidField("config", "配置内容无法读取。");
        }
        return value.deepCopy();
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var object = json.createObjectNode();
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> object.set(name, canonical(value.get(name))));
            return object;
        }
        if (value.isArray()) {
            var array = json.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }
}
