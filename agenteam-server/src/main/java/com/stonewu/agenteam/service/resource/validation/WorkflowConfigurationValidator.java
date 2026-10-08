package com.stonewu.agenteam.service.resource.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workflow.WorkflowGraphValidator;
import com.stonewu.agenteam.service.workflow.WorkflowInputValidation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 资源发布复用正式执行使用的工作流校验，草稿允许保留尚未接通的节点。
 */
@Component
public class WorkflowConfigurationValidator implements ResourceConfigValidator {
    private final WorkflowGraphValidator graphs;

    public WorkflowConfigurationValidator(WorkflowGraphValidator graphs) {
        this.graphs = graphs;
    }

    @Override
    public ResourceKind kind() {
        return ResourceKind.WORKFLOW;
    }

    @Override
    public void draft(JsonNode config) {
        WorkflowInputValidation.validate(config, false);
        Set<String> nodes = new HashSet<>(), edges = new HashSet<>();
        for (var node : config.path("nodes")) {
            if (!nodes.add(node.path("nodeId").asText())) {
                throw ApiException.invalidField("config.nodes", "节点编号不能重复。");
            }
        }
        for (var edge : config.path("edges")) {
            if (!edges.add(edge.path("edgeId").asText())) {
                throw ApiException.invalidField("config.edges", "连线编号不能重复。");
            }
        }
    }

    @Override
    public void validatePublished(JsonNode config) {
        WorkflowInputValidation.validate(config, true);
    }

    @Override
    public void use(AuthContext actor, JsonNode config) {
        graphs.compile(config);
    }

    @Override
    public List<DependencyBinding> dependencies(JsonNode config) {
        var result = new ArrayList<DependencyBinding>();
        int ordinal = 0;
        for (var node : config.path("nodes")) {
            String prefix = "nodes/" + node.path("nodeId").asText() + "/config/";
            var fields = node.path("config");
            DependencyFields.add(result, fields.get("agentVersionId"), "agent", prefix + "agentVersionId", ordinal);
            DependencyFields.add(result, fields.get("skillVersionId"), "skill", prefix + "skillVersionId", ordinal);
            DependencyFields.add(result, fields.get("pluginVersionId"), "plugin", prefix + "pluginVersionId",
                ordinal++);
        }
        return List.copyOf(result);
    }
}
