package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 模型只获得明确绑定工作流的输入结构；执行由原生外部工具暂停交给平台调度。
 */
@Component
public class WorkflowToolCatalog {
    public record Binding(String alias, String resourceId, String versionId, String name, WorkflowGraph graph,
                          JsonNode inputSchema) {
    }

    private final WorkflowGraphValidator graphs;
    private final ResourceJson json;

    public WorkflowToolCatalog(WorkflowGraphValidator graphs, ResourceJson json) {
        this.graphs = graphs;
        this.json = json;
    }

    public Map<String, Binding> list(RunRecord run) {
        Map<String, Binding> result = new LinkedHashMap<>();
        for (var id : run.executionConfig().path("config").path("workflowVersionIds")) {
            var dependency = WorkflowRunDefinitions.dependency(run, "workflow", id.asText());
            var graph = graphs.compile(dependency.path("config"));
            String alias = "workflow_" + UUID.nameUUIDFromBytes(id.asText().getBytes(StandardCharsets.UTF_8)).toString()
                .replace("-", "");
            result.put(alias, new Binding(alias, dependency.path("resourceId").asText(), id.asText(),
                dependency.path("name").asText(),
                graph, graph.nodes().get(graph.startId()).config().path("inputSchema")));
        }
        return Map.copyOf(result);
    }

    public Map<String, ToolBase> tools(Map<String, Binding> bindings) {
        Map<String, ToolBase> result = new LinkedHashMap<>();
        bindings.forEach((name, binding) -> result.put(name, new ExternalWorkflow(binding)));
        return result;
    }

    private final class ExternalWorkflow extends ToolBase {
        private ExternalWorkflow(Binding binding) {
            super(ToolBase.builder().name(binding.alias()).description("执行工作流：" + binding.name())
                .inputSchema(json.object(binding.inputSchema())).externalTool(true).readOnly(false)
                .concurrencySafe(true));
        }

        @Override
        public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
            return Mono.just(PermissionDecision.allow("当前员工已绑定该流程；流程内的外部写入仍需单独确认。"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new IllegalStateException("工作流必须通过框架外部工具暂停执行"));
        }
    }
}
