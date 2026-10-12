package com.stonewu.examples.jsontools;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stonewu.plugin.api.JavaPluginContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

/**
 * 采用严格解析和精确数值，绝不将大整数转成浮点数。
 */
final class JsonDocuments {
    static final int MAX_RESULT_LENGTH = 250_000;
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(1000)
                .maxStringLength(JsonToolDefinitions.MAX_INPUT_LENGTH).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    JsonNode parse(Object input) {
        if (!(input instanceof String text) || text.isBlank()) {
            throw new JsonInputException("EMPTY_JSON", "请提供非空的 JSON 文本。");
        }
        if (text.length() > JsonToolDefinitions.MAX_INPUT_LENGTH) {
            throw new JsonInputException("INPUT_TOO_LARGE", "每份 JSON 最多支持 100000 个字符，请先缩小数据范围。");
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException invalid) {
            var location = invalid.getLocation();
            int line = location == null ? 0 : location.getLineNr();
            int column = location == null ? 0 : location.getColumnNr();
            boolean limited = invalid instanceof StreamConstraintsException;
            String message = limited ? "JSON 超过解析限制：最多嵌套 64 层，单个数字最多 1000 个字符。"
                : "JSON 语法不正确，请检查引号、逗号、括号、重复字段和正文后的多余内容。";
            if (line > 0 && column > 0) {
                message += "位置：第 " + line + " 行，第 " + column + " 列。";
            }
            throw new JsonInputException(limited ? "JSON_LIMIT_EXCEEDED" : "INVALID_JSON", message, line, column, invalid);
        }
    }

    String render(JsonNode value, boolean pretty, int indent) {
        try {
            var printer = new DefaultPrettyPrinter();
            var spaces = new DefaultIndenter(" ".repeat(indent), "\n");
            printer.indentObjectsWith(spaces);
            printer.indentArraysWith(spaces);
            String result = pretty ? mapper.writer(printer).writeValueAsString(value) : mapper.writeValueAsString(value);
            if (result.length() > MAX_RESULT_LENGTH) {
                throw new JsonInputException("RESULT_TOO_LARGE", "处理结果过长，请使用压缩格式或缩小数据范围。");
            }
            return result;
        } catch (JsonProcessingException failed) {
            throw new JsonInputException("JSON_WRITE_FAILED", "JSON 输出失败，请缩小数据范围后重试。", 0, 0, failed);
        }
    }

    JsonNode sort(JsonNode node, JavaPluginContext context) {
        context.cancellation().requireActive();
        if (node.isObject()) {
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            var sorted = mapper.createObjectNode();
            for (String name : names) {
                sorted.set(name, sort(node.get(name), context));
            }
            return sorted;
        }
        if (node.isArray()) {
            var sorted = mapper.createArrayNode();
            for (JsonNode child : node) {
                sorted.add(sort(child, context));
            }
            return sorted;
        }
        return node;
    }

    static String type(JsonNode node) {
        return node.getNodeType().name().toLowerCase(Locale.ROOT);
    }

    static void validatePointer(String pointer) {
        if (pointer.length() > 1024 || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
            throw new JsonInputException("INVALID_POINTER", "路径必须为空或以 / 开头，且不超过 1024 个字符。");
        }
        for (int i = 0; i < pointer.length(); i++) {
            if (pointer.charAt(i) == '~') {
                if (i + 1 >= pointer.length() || (pointer.charAt(i + 1) != '0' && pointer.charAt(i + 1) != '1')) {
                    throw new JsonInputException("INVALID_POINTER", "路径中的波浪号必须写为 ~0，字段名中的斜杠必须写为 ~1。");
                }
                i++;
            }
        }
    }
}
