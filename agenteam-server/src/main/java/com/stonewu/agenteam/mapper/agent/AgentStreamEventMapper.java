package com.stonewu.agenteam.mapper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.agent.entity.AgentStreamEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * 将 AgentScope 的类型化事件转为内部统一结构，公开响应另按平台允许字段生成。
 */
@Component
public class AgentStreamEventMapper {

    private static final List<String> COMMON_FIELDS = List.of("id", "type", "createdAt", "source", "metadata");

    public AgentStreamEvent map(AgentEvent event) {
        Map<String, Object> details = extractDetails(event);
        String type = event.getType().getValue() == null ? event.getType().name() : event.getType().getValue();
        return new AgentStreamEvent(event.getId(), type, event.getSource(), stringValue(details.get("replyId")),
            stringValue(details.get("blockId")), stringValue(details.get("delta")),
            stringValue(details.get("toolCallId")), stringValue(details.get("toolCallName")),
            resultText(event, details), stringValue(details.get("state")), null, event.getCreatedAt(), metadata(event),
            details);
    }

    /**
     * 通过公共 getter 保留每个事件类的所有专属字段，避免只支持少数事件类型。
     * 框架专属字段保留在内部 details 中，不能未经筛选直接返回浏览器。
     */
    private Map<String, Object> extractDetails(AgentEvent event) {
        Map<String, Object> details = new LinkedHashMap<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        for (Method method : event.getClass().getMethods()) {
            if (!isGetter(method)) {
                continue;
            }
            String fieldName = propertyName(method);
            if (COMMON_FIELDS.contains(fieldName)) {
                continue;
            }
            try {
                details.put(fieldName, toJsonValue(method.invoke(event), visited));
            } catch (ReflectiveOperationException ignored) {
                // 某个可选字段读取失败时，不能影响同一事件的其他字段输出。
            }
        }
        return details;
    }

    private static boolean isGetter(Method method) {
        return Modifier.isPublic(method.getModifiers()) && method.getParameterCount() == 0 && !method.getName()
            .equals("getClass") && (method.getName().startsWith("get") || (method.getName()
            .startsWith("is") && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)));
    }

    private static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value.toString();
    }

    private static String resultText(AgentEvent event, Map<String, Object> details) {
        if (!(event instanceof AgentResultEvent)) {
            return null;
        }
        Object result = details.get("result");
        if (result instanceof Map<?, ?> resultMap) {
            Object text = resultMap.get("textContent");
            return text == null ? null : text.toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(AgentEvent event) {
        if (event.getMetadata() == null) {
            return Map.of();
        }
        Object value = toJsonValue(event.getMetadata(), new IdentityHashMap<>());
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object toJsonValue(Object value, IdentityHashMap<Object, Boolean> visited) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof TemporalAccessor || value instanceof Duration) {
            return value.toString();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNodeValue(jsonNode);
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return "[circular-reference]";
        }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, item) -> result.put(String.valueOf(key), toJsonValue(item, visited)));
                return result;
            }
            if (value instanceof Iterable<?> iterable) {
                return streamIterable(iterable, visited);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                ArrayList<Object> result = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    result.add(toJsonValue(Array.get(value, i), visited));
                }
                return result;
            }
            if (value.getClass().getName().startsWith("java.")) {
                return value.toString();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Method method : value.getClass().getMethods()) {
                if (!isGetter(method)) {
                    continue;
                }
                String fieldName = propertyName(method);
                try {
                    result.put(fieldName, toJsonValue(method.invoke(value), visited));
                } catch (ReflectiveOperationException ignored) {
                    // 忽略单个不可读字段，保留其他字段。
                }
            }
            return result.isEmpty() ? value.toString() : result;
        } finally {
            visited.remove(value);
        }
    }

    private static List<Object> streamIterable(Iterable<?> iterable, IdentityHashMap<Object, Boolean> visited) {
        ArrayList<Object> result = new ArrayList<>();
        for (Object item : iterable) {
            result.add(toJsonValue(item, visited));
        }
        return result;
    }

    private static Object jsonNodeValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), jsonNodeValue(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayList<Object> result = new ArrayList<>();
            node.elements().forEachRemaining(item -> result.add(jsonNodeValue(item)));
            return result;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.toString();
    }

    private static String propertyName(Method method) {
        String prefix = method.getName().startsWith("is") ? "is" : "get";
        return decapitalize(method.getName().substring(prefix.length()));
    }
}
