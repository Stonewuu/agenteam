package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph;
import com.stonewu.agenteam.service.resource.ExecutionDependencyService;
import com.stonewu.agenteam.service.resource.FixedDependencyService.ResolvedGraph;
import org.springframework.stereotype.Service;

/**
 * 校验和执行准备共用相同图与固定依赖，不把待测试内容保存成资源草稿。
 */
@Service
public class WorkflowPreparationService {
    private final WorkflowGraphValidator graphs;
    private final ExecutionDependencyService dependencies;
    private final ResourceJson json;

    public WorkflowPreparationService(WorkflowGraphValidator graphs, ExecutionDependencyService dependencies,
                                      ResourceJson json) {
        this.graphs = graphs;
        this.dependencies = dependencies;
        this.json = json;
    }

    public record Prepared(ResourceRecord resource, WorkflowGraph graph, ResolvedGraph dependencies) {
    }

    public Prepared prepare(AuthContext actor, ResourceRecord resource, JsonNode input) {
        var config = json.checkedSize(input);
        var graph = graphs.compile(config);
        var candidate = resource.withConfiguration(config, json.hash(config));
        var prepared = dependencies.prepare(actor, candidate);
        return new Prepared(prepared.resource(), graph, prepared.dependencies());
    }
}
