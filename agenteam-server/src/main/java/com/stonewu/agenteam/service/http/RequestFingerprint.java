package com.stonewu.agenteam.service.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.http.ApiRequestFilter;
import com.stonewu.agenteam.configuration.security.ApplicationSecretKeys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 保留正文文本和有序数组；显式声明为集合的字符串或数字数组排序后参与摘要。
 */
@Service
public class RequestFingerprint {
    private final ApplicationSecretKeys keys;
    private final ObjectMapper json;

    public RequestFingerprint(ApplicationSecretKeys keys, ObjectMapper json) {
        this.keys = keys;
        this.json = json;
    }

    public String bodyHash(HttpServletRequest request, Set<String> unorderedPaths) {
        Object value = request.getAttribute(ApiRequestFilter.JSON_ATTRIBUTE);
        JsonNode body = value instanceof JsonNode node ? node : json.createObjectNode();
        ObjectNode payload = json.createObjectNode();
        payload.set("body", canonical(body, "", unorderedPaths));
        if (request.getAttribute(ApiRequestFilter.BINARY_ATTRIBUTE) instanceof byte[] bytes) {
            // 原始文件只用于摘要；重复请求记录不保存文件正文。
            payload.put("binarySha256", hash(bytes));
        }
        payload.put("ifMatch", request.getHeader("If-Match"));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.requestKey());
            return HexFormat.of().formatHex(mac.doFinal(json.writeValueAsBytes(payload)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法计算请求摘要", exception);
        } catch (Exception exception) {
            if (exception instanceof ApiException api) {
                throw api;
            }
            throw new IllegalStateException("无法序列化请求摘要内容", exception);
        }
    }

    public String operationHash(HttpServletRequest request) {
        return hash(request.getMethod() + " " + request.getRequestURI());
    }

    public String requestKeyHash(HttpServletRequest request) {
        // 保存大小写敏感的摘要，避免数据库字符比较规则把两个不同客户端键视为相同。
        return hash(RequestPreconditions.requestKey(request));
    }

    private JsonNode canonical(JsonNode value, String path, Set<String> unorderedPaths) {
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                result.set(name, canonical(value.get(name), path + "/" + name, unorderedPaths));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            if (unorderedPaths.contains(path)) {
                var values = new TreeMap<String, JsonNode>();
                for (JsonNode item : value) {
                    if (!item.isTextual() && !item.isNumber()) {
                        throw ApiException.invalidField(path.substring(1), "列表中包含无效内容。");
                    }
                    var normalized = canonical(item, path, Set.of());
                    if (values.putIfAbsent(normalized.toString(), normalized) != null) {
                        throw ApiException.invalidField(path.substring(1), "列表不能包含重复记录。");
                    }
                }
                values.values().forEach(result::add);
            } else {
                for (int index = 0; index < value.size(); index++) {
                    result.add(canonical(value.get(index), path + "/" + index, unorderedPaths));
                }
            }
            return result;
        }
        if (value.isNumber()) {
            return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        }
        return value;
    }

    private String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private String hash(byte[] value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境缺少必要的摘要算法", exception);
        }
    }
}
