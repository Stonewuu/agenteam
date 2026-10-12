package com.stonewu.examples.jsontools;

import com.stonewu.plugin.api.JavaPluginContext;
import com.stonewu.plugin.api.JavaToolPlugin;
import com.stonewu.tool.api.ToolSpec;
import org.pf4j.Extension;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只处理调用者传入的文本，不读取文件、不联网，也不需要任何凭据。
 */
@Extension
public final class JsonToolsPlugin implements JavaToolPlugin {
    private static final Logger LOG = System.getLogger(JsonToolsPlugin.class.getName());
    private final JsonDocuments documents = new JsonDocuments();

    @Override
    public List<ToolSpec> tools() {
        return JsonToolDefinitions.tools();
    }

    @Override
    public Map<String, Object> invoke(String tool, Map<String, Object> arguments,
                                      Map<String, Object> configuration, JavaPluginContext context) {
        context.cancellation().requireActive();
        try {
            return switch (tool) {
                case "json_validate" -> validate(arguments);
                case "json_format" -> format(arguments, configuration, context);
                case "json_extract" -> extract(arguments, context);
                case "json_diff" -> new JsonDifferences(documents, context,
                    integer(configuration, "maxDifferences", 50, 1, 200))
                    .compare(documents.parse(arguments.get("left")), documents.parse(arguments.get("right")));
                default -> throw new JsonInputException("UNKNOWN_TOOL", "此插件不支持请求的工具。");
            };
        } catch (JsonInputException invalid) {
            LOG.log(Level.WARNING, "JSON 处理未完成，执行编号 " + context.runId(), sanitizedFailure(invalid));
            var error = new LinkedHashMap<String, Object>();
            error.put("content", invalid.getMessage());
            error.put("isError", true);
            error.put("code", invalid.code);
            if (tool.equals("json_validate")) {
                error.put("valid", false);
            }
            if (invalid.line > 0 && invalid.column > 0) {
                error.put("line", invalid.line);
                error.put("column", invalid.column);
            }
            return error;
        }
    }

    private Map<String, Object> validate(Map<String, Object> arguments) {
        var node = documents.parse(arguments.get("json"));
        return Map.of("content", "JSON 校验通过。", "isError", false, "valid", true, "rootType", JsonDocuments.type(node));
    }

    private Map<String, Object> format(Map<String, Object> arguments, Map<String, Object> configuration,
                                       JavaPluginContext context) {
        int indent = integer(configuration, "indentSize", 2, 2, 4);
        if (indent == 3 || !(configuration.getOrDefault("sortKeys", true) instanceof Boolean sort)) {
            throw new JsonInputException("INVALID_CONFIGURATION", "缩进只能为 2 或 4，字段排序必须选择开启或关闭。");
        }
        Object style = arguments.getOrDefault("style", "pretty");
        if (!"pretty".equals(style) && !"compact".equals(style)) {
            throw new JsonInputException("INVALID_STYLE", "输出格式只能为 pretty 或 compact。");
        }
        var value = documents.parse(arguments.get("json"));
        if (sort) {
            value = documents.sort(value, context);
        }
        return Map.of("content", documents.render(value, "pretty".equals(style), indent), "isError", false,
            "rootType", JsonDocuments.type(value));
    }

    private Map<String, Object> extract(Map<String, Object> arguments, JavaPluginContext context) {
        var value = documents.parse(arguments.get("json"));
        if (!(arguments.get("pointers") instanceof List<?> pointers) || pointers.isEmpty() || pointers.size() > 20) {
            throw new JsonInputException("INVALID_POINTERS", "请提供 1 至 20 条字段路径。");
        }
        var results = new ArrayList<Map<String, Object>>();
        int length = 0;
        for (Object item : pointers) {
            context.cancellation().requireActive();
            if (!(item instanceof String pointer)) {
                throw new JsonInputException("INVALID_POINTER", "每条字段路径必须是文本。");
            }
            JsonDocuments.validatePointer(pointer);
            var extracted = value.at(pointer);
            if (extracted.isMissingNode()) {
                results.add(Map.of("pointer", pointer, "found", false));
            } else {
                String text = documents.render(extracted, false, 2);
                length += text.length();
                if (length > JsonDocuments.MAX_RESULT_LENGTH) {
                    throw new JsonInputException("RESULT_TOO_LARGE", "提取结果过长，请减少路径数量或缩小提取范围。");
                }
                results.add(Map.of("pointer", pointer, "found", true, "type", JsonDocuments.type(extracted), "json", text));
            }
        }
        return Map.of("content", "已检查 " + results.size() + " 条字段路径。", "isError", false, "results", results);
    }

    private static int integer(Map<String, Object> configuration, String name, int fallback, int minimum, int maximum) {
        Object raw = configuration.getOrDefault(name, fallback);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() != number.intValue() || number.intValue() < minimum || number.intValue() > maximum) {
            throw new JsonInputException("INVALID_CONFIGURATION", "插件配置超出允许范围，请检查缩进和差异数量。");
        }
        return number.intValue();
    }

    private static Throwable sanitizedFailure(Throwable source) {
        String detail = source instanceof JsonInputException ? source.getMessage() : source.getClass().getSimpleName();
        var safe = new IllegalArgumentException(detail);
        safe.setStackTrace(source.getStackTrace());
        if (source.getCause() != null) {
            safe.initCause(sanitizedFailure(source.getCause()));
        }
        for (Throwable suppressed : source.getSuppressed()) {
            safe.addSuppressed(sanitizedFailure(suppressed));
        }
        return safe;
    }
}
