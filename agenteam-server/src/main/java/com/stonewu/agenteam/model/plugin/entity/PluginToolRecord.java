package com.stonewu.agenteam.model.plugin.entity;

import com.stonewu.agenteam.model.tool.entity.ToolDefinition;

/**
 * 固定版本的工具记录；紧急停用状态与不可变结构分别保存。
 */
public record PluginToolRecord(String id, String enterpriseId, String pluginVersionId, boolean enabled,
                               ToolDefinition definition,
                               String entryId, PluginToolSource source) {
    public PluginToolRecord(String id, String enterpriseId, String pluginVersionId, boolean enabled,
                            ToolDefinition definition) {
        this(id, enterpriseId, pluginVersionId, enabled, definition, null, null);
    }
}
