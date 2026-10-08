package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 参数比较保留数组顺序；认证字段、适配器指定字段和实际秘密不能进入展示结果。
 */
@Component
public class ToolPayloadMapper {
    private static final Set<String> PRIVATE_FIELDS = Set.of("password", "passwd", "pwd", "secret", "token",
        "authorization",
        "apikey", "accesstoken", "refreshtoken", "clientsecret", "cookie", "setcookie", "credential", "credentials",
        "privatekey", "signingkey");
    private static final Pattern EMBEDDED_SECRET = Pattern.compile(
        "(?i)((?:password|passwd|secret|token|api[_-]?key|authorization|cookie)\\s*[=:]\\s*)([^\\s&,;]+)");
    private final ResourceJson json;

    public ToolPayloadMapper(ResourceJson json) {
        this.json = json;
    }

    public String argumentHash(JsonNode input) {
        return json.hash(normalizeNumbers(input));
    }

    public JsonNode redact(JsonNode input, List<String> paths, List<String> secrets) {
        var protectedValues = new ArrayList<>(secrets);
        protectedValues.addAll(secretValues(input, paths));
        return redact(input, "", paths, protectedValues.stream().distinct().toList());
    }

    public List<String> secretValues(JsonNode input, List<String> paths) {
        List<String> result = new ArrayList<>();
        collectSecrets(input, "", paths, false, result);
        return List.copyOf(result);
    }

    private void collectSecrets(JsonNode node, String path, List<String> paths, boolean privateField,
                                List<String> result) {
        boolean hidden = privateField || paths.contains(path);
        if (node.isTextual() && hidden && !node.asText().isEmpty()) {
            result.add(node.asText());
        } else if (node.isObject()) {
            node.fields().forEachRemaining(field -> collectSecrets(field.getValue(),
                path + "/" + field.getKey().replace("~", "~0").replace("/", "~1"), paths,
                hidden || privateName(field.getKey()), result));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectSecrets(node.get(i), path + "/" + i, paths, hidden, result);
            }
        }
    }

    private boolean privateName(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return PRIVATE_FIELDS.contains(key) || key.endsWith("password") || key.endsWith("secret") || key.endsWith(
            "token") || key.endsWith("apikey");
    }

    private JsonNode redact(JsonNode value, String path, List<String> paths, List<String> secrets) {
        if (paths.contains(path)) {
            return JsonNodeFactory.instance.textNode("[已隐藏]");
        }
        if (value.isObject()) {
            var object = JsonNodeFactory.instance.objectNode();
            value.fields().forEachRemaining(field -> {
                String name = field.getKey();
                if (privateName(name)) {
                    object.put(clean(name, secrets), "[已隐藏]");
                } else {
                    object.set(clean(name, secrets),
                        redact(field.getValue(), path + "/" + name.replace("~", "~0").replace("/", "~1"), paths,
                            secrets));
                }
            });
            return object;
        }
        if (value.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < value.size(); i++) {
                array.add(redact(value.get(i), path + "/" + i, paths, secrets));
            }
            return array;
        }
        return value.isTextual() ? JsonNodeFactory.instance.textNode(clean(value.textValue(), secrets)) : value;
    }

    public String clean(String text, List<String> secrets) {
        String clean = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                clean = clean.replace(secret, "[已隐藏]");
            }
        }
        return EMBEDDED_SECRET.matcher(clean).replaceAll("$1[已隐藏]");
    }

    private JsonNode normalizeNumbers(JsonNode node) {
        if (node.isNumber()) {
            return JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros());
        }
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(field -> result.set(field.getKey(), normalizeNumbers(field.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(normalizeNumbers(value)));
            return result;
        }
        return node;
    }
}
