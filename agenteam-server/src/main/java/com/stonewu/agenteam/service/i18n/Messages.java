package com.stonewu.agenteam.service.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.i18n.entity.UiLanguage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 系统消息的唯一来源；已保存的错误按当前语言显示，原记录不改写。 */
public final class Messages {
    private static final String INDEX = "META-INF/agenteam/messages.index";
    private static final Pattern PARAMETER = Pattern.compile("\\{\\w+\\}");

    private Messages() {
    }

    public static String text(String key, Object... arguments) {
        return in(MessageLanguage.current(), key, arguments);
    }

    /** 日志采用固定英文，不跟随当前用户的界面语言。 */
    public static String log(String key, Object... arguments) {
        return in(UiLanguage.ENGLISH, key, arguments);
    }

    /** 持久化模板和计算版本摘要时使用固定原文，避免语言改变业务内容。 */
    public static String source(String key, Object... arguments) {
        return in(UiLanguage.CHINESE, key, arguments);
    }

    public static String in(UiLanguage language, String key, Object... arguments) {
        Entry entry = Holder.CATALOG.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown application message key: " + key);
        }
        return MessageTemplate.format(entry.values().get(language), arguments);
    }

    public static boolean contains(String key) {
        return Holder.CATALOG.containsKey(key);
    }

    public static void verify() {
        if (Holder.CATALOG.isEmpty()) {
            throw new IllegalStateException("No application message catalog was loaded");
        }
    }

    /** 仅处理明确属于系统的提示、错误及旧执行记录，不处理用户正文。 */
    public static String localize(String message, UiLanguage language) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String key = Holder.EXACT.get(message);
        if (key != null) {
            return in(language, key);
        }
        for (var candidate : Holder.TEMPLATES) {
            Object[] arguments = candidate.template().match(message);
            if (arguments != null) {
                return in(language, candidate.key(), arguments);
            }
        }
        return message;
    }

    private record Entry(Map<UiLanguage, String> values) {
    }

    private record Candidate(String key, MessageTemplate template, int specificity) {
    }

    private static final class Holder {
        private static final Map<String, Entry> CATALOG = load();
        private static final Map<String, String> EXACT = exact();
        private static final List<Candidate> TEMPLATES = templates();

        private static Map<String, Entry> load() {
            var entries = new LinkedHashMap<String, Entry>();
            var resources = new LinkedHashSet<String>();
            var loader = Messages.class.getClassLoader();
            var mapper = new ObjectMapper();
            try {
                for (var index : Collections.list(loader.getResources(INDEX))) {
                    try (var reader = new BufferedReader(new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
                        reader.lines().map(String::trim).filter(line -> !line.isBlank() && !line.startsWith("#"))
                            .forEach(resources::add);
                    }
                }
                for (String resource : resources) {
                    if (!resource.matches("i18n/[A-Za-z0-9_/-]+\\.json")) {
                        throw new IllegalStateException("Invalid application message resource: " + resource);
                    }
                    var urls = Collections.list(loader.getResources(resource));
                    if (urls.isEmpty()) {
                        throw new IllegalStateException("Missing application message resource: " + resource);
                    }
                    for (var url : urls) {
                        try (var input = url.openStream()) {
                            JsonNode root = mapper.readTree(input);
                            if (!root.isObject()) {
                                throw new IllegalStateException("Application message catalog must be an object: " + resource);
                            }
                            var fields = root.fields();
                            while (fields.hasNext()) {
                                var field = fields.next();
                                var languages = new EnumMap<UiLanguage, String>(UiLanguage.class);
                                for (UiLanguage language : UiLanguage.values()) {
                                    JsonNode value = field.getValue().get(language.tag());
                                    if (value == null || !value.isTextual() || value.asText().isBlank()) {
                                        throw new IllegalStateException("Missing translation: " + field.getKey() + "/" + language.tag());
                                    }
                                    languages.put(language, value.asText());
                                }
                                var expected = PARAMETER.matcher(languages.get(UiLanguage.CHINESE)).results()
                                    .map(match -> match.group()).collect(Collectors.toSet());
                                var actual = PARAMETER.matcher(languages.get(UiLanguage.ENGLISH)).results()
                                    .map(match -> match.group()).collect(Collectors.toSet());
                                if (!expected.equals(actual)) {
                                    throw new IllegalStateException("Translation parameters differ: " + field.getKey());
                                }
                                var entry = new Entry(Map.copyOf(languages));
                                Entry previous = entries.putIfAbsent(field.getKey(), entry);
                                if (previous != null && !Objects.equals(previous, entry)) {
                                    throw new IllegalStateException("Conflicting application message key: " + field.getKey());
                                }
                            }
                        }
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to load application messages", failure);
            }
            return Collections.unmodifiableMap(entries);
        }

        private static Map<String, String> exact() {
            var entries = new LinkedHashMap<String, String>();
            CATALOG.forEach((key, value) -> value.values().values().forEach(text -> entries.putIfAbsent(text, key)));
            return Map.copyOf(entries);
        }

        private static List<Candidate> templates() {
            var entries = new ArrayList<Candidate>();
            CATALOG.forEach((key, value) -> value.values().values().stream().distinct()
                .filter(text -> text.matches("(?s).*\\{\\d+\\}.*"))
                .forEach(text -> entries.add(new Candidate(key, new MessageTemplate(text),
                    PARAMETER.matcher(text).replaceAll("").length()))));
            entries.sort(Comparator.comparingInt(Candidate::specificity).reversed());
            return List.copyOf(entries);
        }
    }
}
