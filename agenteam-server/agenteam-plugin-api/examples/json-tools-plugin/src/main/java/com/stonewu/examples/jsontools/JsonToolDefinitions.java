package com.stonewu.examples.jsontools;

import com.stonewu.tool.api.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * 参数说明同时用于平台表单校验和模型工具选择。
 */
final class JsonToolDefinitions {
    static final int MAX_INPUT_LENGTH = 100_000;

    private JsonToolDefinitions() {
    }

    static List<ToolSpec> tools() {
        var document = Map.<String, Object>of("type", "string", "minLength", 1, "maxLength", MAX_INPUT_LENGTH,
            "description", "完整 JSON 文本，不能包含 Markdown 代码围栏。最多 100000 个字符，最多嵌套 64 层。");
        return List.of(
            tool("json_validate", "校验 JSON", "检查 JSON 文本语法。拒绝重复字段、注释、尾随内容和超限嵌套。"
                + "失败时返回原因及可用的行列位置；支持对象、数组、字符串、数字、布尔值和 null。",
                Map.of("json", document), List.of("json")),
            tool("json_format", "格式化 JSON", "校验并格式化或压缩 JSON，保留大整数与小数的数值精度。"
                + "style 为 pretty 时按安装配置缩进，为 compact 时输出单行；字段排序由安装配置决定。",
                Map.of("json", document, "style", Map.of("type", "string", "enum", List.of("pretty", "compact"),
                    "default", "pretty", "description", "pretty 表示缩进排版，compact 表示压缩为单行。")), List.of("json")),
            tool("json_extract", "提取 JSON 字段", "按 JSON Pointer（以斜杠分隔字段的路径）批量读取 JSON。"
                + "例如 /orders/0/amount；空路径表示全文，~1 表示字段名中的斜杠，~0 表示波浪号。"
                + "返回 found 区分缺少字段和 null，json 字段为精确的 JSON 文本；一次最多 20 条路径，不支持通配符。",
                Map.of("json", document, "pointers", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                    "items", Map.of("type", "string", "maxLength", 1024), "description", "需要读取的路径列表。")),
                List.of("json", "pointers")),
            tool("json_diff", "比较 JSON", "比较两份 JSON 的结构，按路径返回 added（新增）、removed（删除）和 changed（修改）。"
                + "忽略对象字段顺序，数值 1 和 1.0 视为相等，数组逐个位置比较。"
                + "before 和 after 为精确 JSON 文本，truncated 为 true 表示仍有差异未展示；不是可直接应用的补丁。",
                Map.of("left", document, "right", document), List.of("left", "right"))
        );
    }

    private static ToolSpec tool(String name, String title, String description,
                                 Map<String, Object> properties, List<String> required) {
        return new ToolSpec(name, title, description, "",
            Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false),
            Map.of("type", "object", "properties", Map.of("content", Map.of("type", "string"),
                "isError", Map.of("type", "boolean")), "required", List.of("content", "isError")),
            Map.of(), "read", false, false, true, List.of(), 10);
    }
}
