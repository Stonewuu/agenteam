package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;

import java.util.Set;

/** 业务模块登记自己的工具处理器，共用平台身份、事务、人工确认和结果记录。 */
public interface PlatformToolHandler {
    Set<String> toolNames();

    JsonNode call(BuiltinExecutionContext context, String name, JsonNode arguments);
}
