package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.ToolQueryResult;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.tool.ToolCallControl;

import java.time.Duration;
import java.util.List;

/**
 * 只有代码显式登记的适配器可以执行内置工具。
 */
public interface BuiltinPluginAdapter {
    String code();

    String name();

    String description();

    default String implementationVersion() {
        return "1";
    }

    List<ToolDefinition> tools();

    /**
     * 请求写出前必须调用 control.beforeSend；重试必须把相同 operationId 交给目标系统去重。
     */
    JsonNode call(String toolName, String operationId, JsonNode arguments, Duration timeout, ToolCallControl control);

    /**
     * 实现必须只读；NOT_EXECUTED 只用于目标系统明确保证该操作没有执行的情况。
     */
    default ToolQueryResult query(String toolName, String operationId, Duration timeout, ToolCallControl control) {
        return ToolQueryResult.unknown();
    }
}
