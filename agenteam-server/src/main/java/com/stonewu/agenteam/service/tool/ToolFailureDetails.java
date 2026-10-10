package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 错误保留原因、调用位置和原始因果关系；请求参数和解析响应不进入日志或模型结果。 */
public final class ToolFailureDetails {
    private static final Pattern ADDRESS = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET = Pattern.compile(
        "(?i)([\"']?(?:password|passwd|secret|token|api[_-]?key|authorization|cookie|code)[\"']?\\s*[:=]\\s*)"
            + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;&}]+)");

    private ToolFailureDetails() {
    }

    public static String clean(String value, List<String> secrets) {
        if (value == null) {
            return "未提供具体原因";
        }
        String safe = value;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                safe = safe.replace(secret, "[已隐藏]");
            }
        }
        safe = ADDRESS.matcher(safe).replaceAll("[地址已隐藏]");
        safe = SECRET.matcher(safe).replaceAll("$1[已隐藏]");
        safe = safe.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [已隐藏]")
            .replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
        return safe.length() > 1200 ? safe.substring(0, 1200) : safe;
    }

    public static Throwable safe(Throwable error, List<String> secrets) {
        return safe(error, secrets, new IdentityHashMap<>());
    }

    public static List<String> inputValues(Object input) {
        var values = new ArrayList<String>();
        collect(input, values, new IdentityHashMap<>(), 0);
        return List.copyOf(values);
    }

    private static void collect(Object input, List<String> values, Map<Object, Boolean> visited, int depth) {
        if (input == null || depth > 64 || visited.size() >= 10000 || visited.put(input, true) != null) {
            return;
        }
        if (input instanceof String text && !text.isBlank()) {
            values.add(text);
        } else if (input instanceof Map<?, ?> map) {
            map.values().forEach(value -> collect(value, values, visited, depth + 1));
        } else if (input instanceof List<?> list) {
            list.forEach(value -> collect(value, values, visited, depth + 1));
        }
    }

    private static Throwable safe(Throwable error, List<String> secrets, Map<Throwable, Throwable> visited) {
        if (error == null || visited.containsKey(error)) {
            return null;
        }
        String message = error instanceof JsonProcessingException ? "响应解析失败" : clean(error.getMessage(), secrets);
        var result = new IllegalStateException(error.getClass().getName() + "：" + message);
        visited.put(error, result);
        result.setStackTrace(error.getStackTrace());
        var cause = safe(error.getCause(), secrets, visited);
        if (cause != null) {
            result.initCause(cause);
        }
        for (var suppressed : error.getSuppressed()) {
            var item = safe(suppressed, secrets, visited);
            if (item != null) {
                result.addSuppressed(item);
            }
        }
        return result;
    }
}
