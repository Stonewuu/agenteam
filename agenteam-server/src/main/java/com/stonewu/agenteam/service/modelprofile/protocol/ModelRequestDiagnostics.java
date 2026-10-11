package com.stonewu.agenteam.service.modelprofile.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anthropic.errors.AnthropicServiceException;
import com.google.genai.errors.ApiException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.transport.HttpTransportException;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 在开发库读取错误之前过滤正文；异常向外传播时只保留脱敏原因和完整调用堆栈。
 */
public final class ModelRequestDiagnostics {
    private static final Logger LOG = LoggerFactory.getLogger(ModelRequestDiagnostics.class);
    private static final Pattern SECRET = Pattern.compile("(?i)(?:Bearer\\s+\\S+|(?:api[-_]?key|token|secret|password|authorization)\\s*[:=]\\s*[^\\s,;]+)");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'|`[^`]*`");
    private static final Pattern QUERY = Pattern.compile("(https?://[^\\s?\"'<>]+)\\?[^\\s\"'<>]*");
    private final ObjectMapper json;
    private final String callId;
    private final List<String> privateValues = new ArrayList<>();
    private String method = "POST";
    private String path = "/";
    private volatile Integer httpStatus;
    private volatile String requestId;
    private volatile String errorCode;
    private volatile String reason;
    private volatile String category;

    public ModelRequestDiagnostics(ObjectMapper json, String callId, String key, List<Msg> messages) {
        this.json = json;
        this.callId = callId;
        remember(key);
        for (var message : messages) {
            remember(message.getMetadata());
            for (var block : message.getContent()) {
                if (block instanceof TextBlock text) {
                    remember(text.getText());
                } else if (block instanceof ThinkingBlock thinking) {
                    remember(thinking.getMetadata());
                    remember(thinking.getThinking());
                } else if (block instanceof ToolUseBlock tool) {
                    remember(tool.getMetadata());
                    remember(tool.getInput());
                } else if (block instanceof ToolResultBlock tool) {
                    tool.getOutput().forEach(output -> {
                        if (output instanceof TextBlock text) {
                            remember(text.getText());
                        }
                    });
                }
            }
        }
        privateValues.sort(Comparator.comparingInt(String::length).reversed());
    }

    public void request(Request request) {
        method = request.method();
        path = request.url().encodedPath();
    }

    public Response response(Response response) throws IOException {
        httpStatus = response.code();
        requestId = identifier(response.header("x-request-id"));
        if (requestId == null) {
            requestId = identifier(response.header("request-id"));
        }
        if (response.isSuccessful()) {
            return response;
        }
        category = "HTTP_ERROR";
        try (var peek = response.peekBody(16384)) {
            JsonNode body = json.readTree(peek.bytes());
            captureError(body == null ? null : body.path("error"));
        } catch (JsonProcessingException failure) {
            reason = "厂商错误响应无法解析";
            LOG.warn("模型错误响应无法解析，调用={}，方法={}，路径={}，HTTP状态={}，平台排障编号={}",
                callId, method, path, httpStatus, requestId, safeCause(failure, new IdentityHashMap<>()));
        }
        if (reason == null) {
            reason = "模型服务拒绝请求，HTTP状态=" + response.code();
        }
        // 官方 Chat 传输会记录整个错误响应，因此只向下游提供必要的脱敏错误字段。
        var safe = json.createObjectNode();
        safe.putObject("error").put("message", reason).put("type", errorCode == null ? "provider_error" : errorCode)
            .put("code", errorCode == null ? "provider_error" : errorCode);
        safe.put("message", reason);
        response.close();
        return response.newBuilder().body(ResponseBody.create(safe.toString(), MediaType.get("application/json")))
            .removeHeader("Content-Length").removeHeader("Content-Encoding").build();
    }

    public void captureError(JsonNode error) {
        if (error == null || error.isMissingNode() || error.isNull()) {
            return;
        }
        category = "PROVIDER_ERROR";
        errorCode = identifier(error.path("code").asText(null));
        if (error.path("code").isNumber()) {
            errorCode = identifier(error.path("status").asText(null));
        }
        if (errorCode == null) {
            errorCode = identifier(error.path("type").asText(null));
        }
        String message = error.path("message").asText(null);
        reason = message == null ? "厂商返回错误，错误码=" + errorCode : clean(message);
    }

    public RuntimeException failure(Throwable failure) {
        captureNativeFailure(failure);
        if (category == null) {
            category = failure instanceof TimeoutException ? "TIMEOUT" : "MODEL_FAILURE";
        }
        if (reason == null) {
            reason = networkReason(failure);
        }
        var safe = safeCause(failure, new IdentityHashMap<>());
        LOG.warn("模型请求失败，调用={}，方法={}，路径={}，HTTP状态={}，平台错误码={}，原因={}，平台排障编号={}",
            callId, method, path, httpStatus, errorCode, reason, requestId, safe);
        return httpStatus == null ? new HttpTransportException(reason, safe)
            : new HttpTransportException(reason, httpStatus, null, safe);
    }

    private void captureNativeFailure(Throwable failure) {
        if (reason != null) {
            return;
        }
        var seen = new IdentityHashMap<Throwable, Boolean>();
        for (var current = failure; current != null && seen.put(current, true) == null; current = current.getCause()) {
            if (current instanceof AnthropicServiceException nativeFailure) {
                captureError(nativeFailure.body().convert(JsonNode.class).path("error"));
            } else if (current instanceof ApiException nativeFailure) {
                var error = json.createObjectNode().put("code", nativeFailure.status()).put("message", nativeFailure.message());
                captureError(error);
            }
        }
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String requestId() {
        return requestId;
    }

    public String errorCode() {
        return errorCode;
    }

    public String category() {
        return category;
    }

    private String networkReason(Throwable failure) {
        var seen = new IdentityHashMap<Throwable, Boolean>();
        for (Throwable current = failure; current != null && seen.put(current, true) == null; current = current.getCause()) {
            if (current instanceof IOException && !(current instanceof JsonProcessingException)) {
                return clean(current.getMessage());
            }
            if (current instanceof TimeoutException) {
                return "模型请求超过本次执行时间限制（timeout）";
            }
        }
        return "模型响应处理失败，具体位置见异常堆栈";
    }

    private Throwable safeCause(Throwable original, Map<Throwable, Throwable> visited) {
        if (original == null || visited.containsKey(original)) {
            return null;
        }
        boolean network = original instanceof IOException && !(original instanceof JsonProcessingException);
        var safe = new IllegalStateException(original.getClass().getName()
            + (network ? "：" + clean(original.getMessage()) : "：模型请求处理异常"));
        visited.put(original, safe);
        safe.setStackTrace(original.getStackTrace());
        var cause = safeCause(original.getCause(), visited);
        if (cause != null) {
            safe.initCause(cause);
        }
        for (var suppressed : original.getSuppressed()) {
            var item = safeCause(suppressed, visited);
            if (item != null) {
                safe.addSuppressed(item);
            }
        }
        return safe;
    }

    private String clean(String value) {
        if (value == null) {
            return "厂商未提供错误说明";
        }
        String safe = value;
        for (var secret : privateValues) {
            safe = safe.replace(secret, "[已隐藏]");
        }
        safe = QUERY.matcher(safe).replaceAll("$1?[已隐藏]");
        safe = SECRET.matcher(safe).replaceAll("[已隐藏]");
        safe = QUOTED.matcher(safe).replaceAll("[已隐藏]").replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        return safe.substring(0, Math.min(safe.length(), 1200));
    }

    private String identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:/-]{1,100}") && privateValues.stream().noneMatch(value::contains) ? value : null;
    }

    private void remember(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            privateValues.add(text);
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(this::remember);
        } else if (value instanceof Iterable<?> values) {
            values.forEach(this::remember);
        }
    }
}
