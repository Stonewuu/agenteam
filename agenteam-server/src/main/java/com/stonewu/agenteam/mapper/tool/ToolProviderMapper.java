package com.stonewu.agenteam.mapper.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.tool.api.ToolSource;
import com.stonewu.tool.api.ToolSpec;
import org.springframework.stereotype.Component;

/**
 * 公共扩展接口只使用标准 Java 数据类型，数据库和 JSON 库对象留在宿主。
 */
@Component
public class ToolProviderMapper {
    private final ResourceJson json;

    public ToolProviderMapper(ResourceJson json) {
        this.json = json;
    }

    public ToolSource source(ExecutionToolBinding binding) {
        var source = binding.source();
        return new ToolSource(source == null ? binding.config().path("pluginType").asText() : source.type(),
            binding.sourceResourceId(), binding.sourceVersionId(),
            source == null ? binding.config().path("implementationVersion")
                .asText("1") : source.implementationVersion(), json.object(binding.config()));
    }

    public ToolSpec spec(ToolDefinition tool, ToolSource source) {
        return new ToolSpec(tool.name(), ToolDisplayNameMapper.name(tool, "plugin", json.tree(source.configuration())),
            tool.description(), tool.schemaHash(),
            json.object(tool.inputSchema()),
            tool.outputSchema() == null || tool.outputSchema().isNull() ? null : json.object(tool.outputSchema()),
            json.object(tool.annotations()), tool.operationClass(), tool.supportsDeduplication(),
            tool.supportsResultQuery(), tool.supportsCancel(), tool.redactPaths(), tool.timeoutSeconds());
    }

    public ToolDefinition definition(ToolSpec tool) {
        ObjectNode annotations = json.tree(tool.annotations()).deepCopy();
        if (tool.displayName() != null && !tool.displayName().isBlank() && !tool.displayName().equals(tool.name())) {
            annotations.put("title", tool.displayName());
        }
        return new ToolDefinition(tool.name(), tool.description(), tool.schemaHash(), json.tree(tool.inputSchema()),
            tool.outputSchema() == null ? null : json.tree(tool.outputSchema()),
            annotations, tool.operationClass(), tool.supportsDeduplication(), tool.supportsResultQuery(),
            tool.supportsCancel(), tool.redactPaths(), tool.timeoutSeconds());
    }
}
