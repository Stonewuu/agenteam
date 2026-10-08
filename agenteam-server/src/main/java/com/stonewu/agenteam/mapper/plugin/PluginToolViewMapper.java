package com.stonewu.agenteam.mapper.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolDisplayNameMapper;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRecord;
import com.stonewu.agenteam.model.plugin.entity.PluginToolSource;
import com.stonewu.agenteam.model.plugin.response.PluginToolView;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

/**
 * 固定工具只公开选择与填写参数所需的信息。
 */
@Component
public class PluginToolViewMapper {
    private final ResourceJson json;

    public PluginToolViewMapper(ResourceJson json) {
        this.json = json;
    }

    public PluginToolView map(String id, ToolDefinition tool, boolean enabled, JsonNode config) {
        return map(id, id, tool, enabled, config, null);
    }

    public PluginToolView map(PluginToolRecord tool, JsonNode config) {
        return map(tool.id(), tool.entryId() == null ? tool.id() : tool.entryId(), tool.definition(), tool.enabled(),
            tool.source() == null ? config : tool.source().config(), tool.source());
    }

    public PluginToolView map(String id, String entryId, ToolDefinition tool, boolean enabled, JsonNode config,
                              PluginToolSource source) {
        return new PluginToolView(id, tool.name(), ToolDisplayNameMapper.name(tool, "plugin", config),
            tool.description(), tool.operationClass(), enabled, tool.supportsDeduplication(),
            tool.supportsResultQuery(),
            json.object(tool.inputSchema()),
            tool.outputSchema() == null || tool.outputSchema().isNull() ? null : json.object(tool.outputSchema()),
            entryId, source == null ? null : source.resourceId(), source == null ? null : source.versionId(),
            source == null ? null : source.name());
    }
}
