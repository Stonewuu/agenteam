package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.resource.FixedDependencyService.ResolvedGraph;
import org.springframework.stereotype.Component;

/**
 * 固定入口资源和全部依赖版本，运行时不重新选择最新版本。
 */
@Component
public class ExecutionConfigurationMapper {
    private final ObjectMapper json;

    public ExecutionConfigurationMapper(ObjectMapper json) {
        this.json = json;
    }

    public ObjectNode freeze(ResourceKind kind, String id, String name, JsonNode config, ResolvedGraph graph) {
        if (kind != ResourceKind.AGENT && kind != ResourceKind.WORKFLOW) {
            throw new IllegalArgumentException("当前资源不是执行入口");
        }
        var result = json.createObjectNode().put(kind == ResourceKind.AGENT ? "agentId" : "workflowId", id)
            .put("name", name);
        result.set("config", config.deepCopy());
        var all = result.putArray("dependencies");
        for (var dependency : graph.resources()) {
            var item = all.addObject().put("resourceId", dependency.resource().id())
                .put("versionId", dependency.version().id())
                .put("kind", dependency.resource().kind().code()).put("name", dependency.version().name());
            item.set("config", dependency.version().config());
        }
        return result;
    }
}
