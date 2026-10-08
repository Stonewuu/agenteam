package com.stonewu.agenteam.model.tool.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.plugin.entity.PluginToolSource;

import java.util.Objects;

/**
 * 一次执行固定的真实资源与工具；平台知识和数据工具没有插件工具记录。
 */
public record ExecutionToolBinding(String resourceId, String resourceVersionId, String resourceKind,
                                   String resourceName,
                                   String pluginToolId, ToolDefinition definition, JsonNode config, String entryId,
                                   PluginToolSource source) {
    public ExecutionToolBinding(String resourceId, String resourceVersionId, String resourceKind, String resourceName,
                                String pluginToolId, ToolDefinition definition, JsonNode config) {
        this(resourceId, resourceVersionId, resourceKind, resourceName, pluginToolId, definition, config, null, null);
    }

    public String alias() {
        return alias(definition.name(), pluginToolId, resourceVersionId, resourceId);
    }

    public static String alias(String name, String plugin, String version, String resource) {
        return "platform_" + (plugin != null ? plugin.replace("-",
            "") : name + "_" + (version == null ? "draft_" + resource : version).replace("-", ""));
    }

    public boolean readOnly() {
        return definition.operationClass().equals("read");
    }

    public boolean matches(ToolCallRecord call) {
        return Objects.equals(pluginToolId, call.pluginToolId()) && Objects.equals(resourceVersionId,
            call.resourceVersionId())
            && resourceId.equals(call.resourceId()) && definition.name().equals(call.toolName());
    }

    public String sourceResourceId() {
        return source == null ? resourceId : source.resourceId();
    }

    public String sourceVersionId() {
        return source == null ? resourceVersionId : source.versionId();
    }

    public ToolInvocationIdentity identity() {
        return new ToolInvocationIdentity(sourceResourceId(), sourceVersionId(), definition.name(),
            definition.schemaHash(), config);
    }
}
