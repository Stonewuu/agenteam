package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.model.resource.entity.ResolvedDependency;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ExecutionToolCatalog;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 节点只能使用已固定智能体声明的技能和插件版本实际提供的工具。
 */
@Component
public class WorkflowNodeDependencies {
    private final PluginToolMapper tools;

    public WorkflowNodeDependencies(PluginToolMapper tools) {
        this.tools = tools;
    }

    public void validate(String enterprise, JsonNode config, Map<String, ResolvedDependency> dependencies) {
        int index = 0;
        for (var node : config.path("nodes")) {
            String field = "config.nodes[" + index++ + "].config";
            var settings = node.path("config");
            String type = node.path("type").asText();
            if (Set.of("agent", "skill").contains(type)) {
                var agent = dependencies.get(settings.path("agentVersionId").asText());
                if (agent == null || !Set.of("chat", "task")
                    .contains(agent.version().config().path("agentType").asText())) {
                    throw ApiException.invalidField(field + ".agentVersionId",
                        "节点只能使用可用的对话型或任务型智能体。");
                }
                if (type.equals("skill") && !ids(agent.version().config().path("skillVersionIds")).contains(
                    settings.path("skillVersionId").asText())) {
                    throw ApiException.invalidField(field + ".skillVersionId",
                        "所选技能没有包含在该智能体的固定能力中，请重新选择。");
                }
            }
            if (type.equals("tool")) {
                String version = settings.path("pluginVersionId").asText(), key = ExecutionToolCatalog.selectionKey(
                    settings);
                var plugin = dependencies.get(version);
                if (plugin == null || tools.list(enterprise, version).stream()
                    .filter(tool -> tool.enabled() && (key.equals(tool.entryId()) || key.equals(tool.id())
                        || !PluginConfigurationMapper.modern(plugin.version().config()) && !settings.hasNonNull(
                        "toolId") && key.equals(tool.definition().name()))).count() != 1) {
                    throw ApiException.invalidField(field + ".toolId", "所选插件版本没有提供这个可用工具，请重新选择。");
                }
            }
        }
    }

    private Set<String> ids(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }
}
