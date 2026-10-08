package com.stonewu.agenteam.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在替换为用户提示之前记录平台原因。只读取错误字段，隐藏凭证和可能被平台回显的请求内容。
 */
final class ChannelRequestDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(IntegrationHttpClient.class);
    private static final String HIDDEN = "[已隐藏]";
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)([\"']?(?:access_token|refresh_token|tenant_access_token|app_access_token|user_access_token|"
            + "corpsecret|app_secret|client_secret|secret|password|authorization|code_verifier|code)[\"']?\\s*[:=]\\s*)"
            + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;&}]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?\"'<>]+)\\?[^\\s\"'<>]*");
    private static final Set<String> RECIPIENT_ERRORS = Set.of("invaliduser", "invalidparty", "invalidtag", "unlicenseduser");
    private final List<String> privateValues = new ArrayList<>();

    ChannelRequestDiagnostics(Map<String, String> parameters, JsonNode body, String bearer, ObjectMapper json) {
        parameters.values().forEach(this::remember);
        remember(bearer);
        rememberBody(body, json);
    }

    void response(String connection, Request request, int status, JsonNode body, String trace) {
        if (body == null || !body.isObject()) {
            log(connection, request, status, "—", "响应不是有效的 JSON 对象", trace);
            return;
        }
        // 返回中的令牌同样不能通过平台错误文本流入日志。
        rememberResponseCredentials(body);
        JsonNode code = body.has("errcode") ? body.path("errcode") : body.path("code");
        var rejected = new ArrayList<String>();
        for (String field : RECIPIENT_ERRORS) {
            JsonNode value = body.path(field);
            if (!value.isMissingNode() && !value.isNull() && (!value.isTextual() || !value.asText().isBlank())) {
                rejected.add(field);
            }
        }
        boolean invalidCode = !code.isMissingNode() && (!code.isIntegralNumber() || code.asLong() != 0);
        if (status >= 200 && status < 300 && !invalidCode && !body.hasNonNull("error") && rejected.isEmpty()) {
            return;
        }
        var reasons = new ArrayList<String>();
        for (String field : List.of("errmsg", "msg", "error_description")) {
            addText(reasons, body.path(field));
        }
        addText(reasons, body.path("error"));
        addText(reasons, body.path("error").path("message"));
        if (!rejected.isEmpty()) {
            reasons.add("平台拒绝的接收对象字段：" + String.join("、", rejected));
        }
        if (trace == null && body.path("error").path("log_id").isTextual()) {
            String candidate = body.path("error").path("log_id").asText();
            trace = candidate.matches("[A-Za-z0-9_-]{1,191}") ? candidate : null;
        }
        log(connection, request, status, code.isValueNode() ? code.asText() : "—",
            reasons.isEmpty() ? "平台未提供错误说明" : String.join("；", reasons), trace);
    }

    void failure(String connection, Request request, Integer status, String trace, Throwable cause, boolean malformed) {
        // JSON 解析异常可能夹带响应正文，只保留类型和位置堆栈；网络异常保留脱敏后的具体原因。
        Throwable safe = safeCause(cause, !malformed, new IdentityHashMap<>());
        LOG.warn("渠道请求异常，接入={}，方法={}，主机={}，路径={}，HTTP状态={}，平台排障编号={}，原因={}",
            clean(connection), request.method(), request.url().host(), request.url().encodedPath(), status,
            clean(trace), malformed ? "平台响应无法解析为 JSON" : clean(cause.getMessage()), safe);
    }

    private void log(String connection, Request request, int status, String code, String reason, String trace) {
        LOG.warn("渠道平台返回错误，接入={}，方法={}，主机={}，路径={}，HTTP状态={}，平台错误码={}，平台原因={}，平台排障编号={}",
            clean(connection), request.method(), request.url().host(), request.url().encodedPath(), status,
            clean(code), clean(reason), clean(trace));
    }

    private Throwable safeCause(Throwable source, boolean includeMessage, Map<Throwable, Throwable> visited) {
        if (source == null || visited.containsKey(source)) {
            return null;
        }
        var safe = new IllegalStateException(source.getClass().getName()
            + (includeMessage ? "：" + clean(source.getMessage()) : "：响应解析失败"));
        visited.put(source, safe);
        safe.setStackTrace(source.getStackTrace());
        Throwable cause = safeCause(source.getCause(), includeMessage, visited);
        if (cause != null) {
            safe.initCause(cause);
        }
        for (Throwable suppressed : source.getSuppressed()) {
            Throwable item = safeCause(suppressed, includeMessage, visited);
            if (item != null) {
                safe.addSuppressed(item);
            }
        }
        return safe;
    }

    String clean(String source) {
        if (source == null) {
            return "—";
        }
        String result = source;
        for (String value : privateValues.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            result = result.replace(value, HIDDEN);
        }
        result = URL_QUERY.matcher(result).replaceAll("$1?" + HIDDEN);
        result = BEARER.matcher(result).replaceAll("Bearer " + HIDDEN);
        result = CREDENTIAL.matcher(result).replaceAll("$1" + HIDDEN);
        result = result.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        return result.length() > 1200 ? result.substring(0, 1200) + "…" : result;
    }

    private void remember(String value) {
        if (value != null && !value.isBlank()) {
            privateValues.add(value);
            String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
            privateValues.add(encoded);
            privateValues.add(encoded.replace("+", "%20"));
        }
    }

    private void rememberBody(JsonNode body, ObjectMapper json) {
        if (body == null) {
            return;
        }
        if (body.isTextual()) {
            String value = body.asText();
            remember(value);
            // 飞书消息内容使用 JSON 字符串包装，正文也可能单独被回显。
            if (value.startsWith("{") && value.endsWith("}")) {
                try {
                    rememberBody(json.readTree(value), json);
                } catch (JsonProcessingException ignored) {
                    // 普通正文无须解析；上面已保存整个值用于隐藏。
                }
            }
        } else if (body.isContainerNode()) {
            body.forEach(value -> rememberBody(value, json));
        }
    }

    private void rememberResponseCredentials(JsonNode body) {
        if (body.isObject()) {
            body.properties().forEach(entry -> {
                if (entry.getKey().matches("(?i).*(?:token|secret|password|code_verifier).*")) {
                    remember(entry.getValue().asText(null));
                } else {
                    rememberResponseCredentials(entry.getValue());
                }
            });
        } else if (body.isArray()) {
            body.forEach(this::rememberResponseCredentials);
        }
    }

    private static void addText(List<String> values, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            values.add(node.asText());
        }
    }
}
