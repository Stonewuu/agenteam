package com.example;

import com.stonewu.plugin.api.JavaPluginContext;
import com.stonewu.plugin.api.JavaToolPlugin;
import com.stonewu.tool.api.ToolSpec;
import org.pf4j.Extension;

import java.util.List;
import java.util.Map;

/**
 * 可直接构建上传的最小示例，配置和模型参数分别接收。
 */
@Extension
public class EchoPlugin implements JavaToolPlugin {
    @Override
    public List<ToolSpec> tools() {
        return List.of(new ToolSpec("echo", "格式化文本", "给文本添加安装配置指定的前缀。",
            "", Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string", "maxLength", 2000)),
            "required", List.of("text"), "additionalProperties", false),
            Map.of("type", "object", "properties", Map.of("content", Map.of("type", "string")),
                "required", List.of("content")), Map.of(), "read", false, false, true, List.of(), 10));
    }

    @Override
    public Map<String, Object> invoke(String tool, Map<String, Object> arguments,
                                      Map<String, Object> configuration, JavaPluginContext context) {
        context.cancellation().requireActive();
        if (!tool.equals("echo")) {
            throw new IllegalArgumentException("不支持此工具");
        }
        return Map.of("content", configuration.getOrDefault("prefix", "") + arguments.get("text").toString());
    }
}
