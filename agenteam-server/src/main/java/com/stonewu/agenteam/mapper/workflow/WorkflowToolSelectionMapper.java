package com.stonewu.agenteam.mapper.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workflow.WorkflowNodes;
import org.springframework.stereotype.Component;

/**
 * 旧节点的方法名在保存时换成唯一工具条目；历史发布快照继续保留原文。
 */
@Component
public class WorkflowToolSelectionMapper {
    private final PluginToolMapper tools;

    public WorkflowToolSelectionMapper(PluginToolMapper tools) {
        this.tools = tools;
    }

    public JsonNode normalize(AuthContext actor, JsonNode config) {
        ObjectNode result = config.deepCopy();
        for (var node : WorkflowNodes.all(result)) {
            if (!node.path("type").asText().equals("tool") || !(node.path(
                "config") instanceof ObjectNode settings) || !settings.has("toolName")) {
                continue;
            }
            if (!settings.hasNonNull("toolId")) {
                String name = settings.path("toolName").asText();
                if (settings.hasNonNull("pluginVersionId") && !name.isBlank() && !name.equals("待选择")) {
                    var matches = tools.list(actor.enterpriseId(), settings.path("pluginVersionId").asText()).stream()
                        .filter(tool -> tool.definition().name().equals(name)).toList();
                    if (matches.size() != 1) {
                        throw ApiException.invalidField("config.nodes", "无法在当前版本中确定要使用的工具，请重新选择工具。");
                    }
                    var tool = matches.getFirst();
                    settings.put("toolId", tool.entryId() == null ? tool.id() : tool.entryId());
                } else {
                    settings.putNull("toolId");
                }
            }
            settings.remove("toolName");
        }
        return result;
    }
}
