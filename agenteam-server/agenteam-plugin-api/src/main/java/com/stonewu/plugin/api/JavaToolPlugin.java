package com.stonewu.plugin.api;

import com.stonewu.tool.api.ToolSpec;
import org.pf4j.ExtensionPoint;

import java.util.List;
import java.util.Map;

/**
 * 一个程序包提供一个扩展实现，工具定义不得依赖安装配置或执行外部修改。
 */
public interface JavaToolPlugin extends ExtensionPoint {
    String API_VERSION = "1.0.0";

    List<ToolSpec> tools();

    Map<String, Object> invoke(String tool, Map<String, Object> arguments,
                               Map<String, Object> configuration, JavaPluginContext context);
}
