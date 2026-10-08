package com.stonewu.agenteam.model.plugin.entity;

import com.stonewu.agenteam.model.resource.response.ConnectionCheckView;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * 远程连接完成后交给短事务保存的结果，不包含凭据。
 */
public record PluginCheckResult(String startedAt, ConnectionCheckView view, List<ToolDefinition> tools,
                                Map<String, List<String>> fieldErrors) {
}
