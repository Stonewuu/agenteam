package com.stonewu.agenteam.model.tool.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;

/**
 * 只有来源、版本、工具定义和执行设置均一致的引用才可合并。
 */
public record ToolInvocationIdentity(String sourceId, String versionId, String name, String schemaHash,
                                     JsonNode config) {
    public static ToolInvocationIdentity of(ResourceVersionRecord version, PluginToolRecord tool) {
        var source = tool.source();
        return new ToolInvocationIdentity(source == null ? version.resourceId() : source.resourceId(),
            source == null ? version.id() : source.versionId(),
            tool.definition().name(), tool.definition().schemaHash(),
            source == null ? version.config() : source.config());
    }
}
