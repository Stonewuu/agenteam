package com.stonewu.tool.api;

import java.util.List;
import java.util.Map;

/**
 * 工具来源的扩展入口；发布资格、授权及人工确认由宿主执行。
 */
public interface ToolProvider {

    String API_VERSION = "1.0.0";

    String type();

    List<ToolSpec> tools(ToolSource source, ToolContext context);

    Map<String, Object> invoke(ToolSource source, ToolSpec tool, Map<String, Object> arguments, ToolContext context);

    default ToolQuery query(ToolSource source, ToolSpec tool, ToolContext context) {
        return ToolQuery.unknown();
    }
}
