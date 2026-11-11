package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 内置插件的原始定义、显示翻译及外观共用目录，原文用于固定版本内容。 */
public final class BuiltinPluginPresentation {
    private static final JsonNode CATALOG = load();

    private BuiltinPluginPresentation() {
    }

    public static String name(String code) {
        return CATALOG.path(code).path("name").path("source").asText();
    }

    public static String description(String code) {
        return CATALOG.path(code).path("description").path("source").asText();
    }

    public static String icon(String code) {
        return CATALOG.path(code).path("icon").asText("Box");
    }

    public static String color(String code) {
        return CATALOG.path(code).path("color").asText("purple");
    }

    public static List<String> matchingCodes(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        var result = new ArrayList<String>();
        var entries = CATALOG.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            boolean matches = false;
            for (String field : List.of("name", "description")) {
                for (String language : List.of("source", "zh-CN", "en")) {
                    matches |= entry.getValue().path(field).path(language).asText()
                        .toLowerCase(Locale.ROOT).contains(normalized);
                }
            }
            if (matches) {
                result.add(entry.getKey());
            }
        }
        return List.copyOf(result);
    }

    private static JsonNode load() {
        try (var input = BuiltinPluginPresentation.class.getResourceAsStream("/catalog/builtin-plugins.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing built-in plugin catalog");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load built-in plugin catalog", failure);
        }
    }
}
