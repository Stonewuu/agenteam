package com.stonewu.agenteam.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.stonewu.agenteam.mapper.workflow.WorkflowEventMapper.Identity;
import com.stonewu.agenteam.model.workflow.entity.WorkflowGraph.Node;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/** 每次循环拥有独立检查点；恢复时读取已完成结果，不重复调用工具或请求人工确认。 */
@Component
public class WorkflowLoopNodeExecutor {
    private final WorkflowGraphValidator graphs;
    private final WorkflowValues values;
    private final ObjectMapper json;
    private final Clock clock;

    public WorkflowLoopNodeExecutor(WorkflowGraphValidator graphs, WorkflowValues values, ObjectMapper json, Clock clock) {
        this.graphs = graphs;
        this.values = values;
        this.json = json;
        this.clock = clock;
    }

    public Mono<Void> execute(WorkflowRunContext context, Identity identity, WorkflowTraversal parent, Node node,
                              JsonNode input, BiFunction<Identity, WorkflowTraversal, Mono<Void>> execute) {
        return iteration(context, identity, parent, node, input, execute, 0, json.createArrayNode());
    }

    private Mono<Void> iteration(WorkflowRunContext context, Identity identity, WorkflowTraversal parent, Node node,
                                JsonNode input, BiFunction<Identity, WorkflowTraversal, Mono<Void>> execute,
                                int index, ArrayNode results) {
        return Mono.defer(() -> {
            context.requireActive();
            if (index >= input.path("items").size()) {
                var output = json.createObjectNode().put("iterations", index);
                output.set("results", results);
                context.complete(identity, parent, node, output, "default");
                return Mono.empty();
            }
            String id = UUID.nameUUIDFromBytes((identity.id() + ":" + node.id() + ":" + index)
                .getBytes(StandardCharsets.UTF_8)).toString();
            String group = context.frames().read(mapper -> mapper.presentation()
                .id("workflow-node:" + identity.id() + ":" + node.id()));
            var childIdentity = new Identity(id, identity.resourceId(), identity.versionId(),
                node.name() + " · 第 " + (index + 1) + " 次", group, group);
            var graph = graphs.compile(node.config().path("body"));
            var saved = context.savedInvocation(id);
            if (saved == null) {
                var iterationInput = json.createObjectNode().put("index", index).put("number", index + 1);
                iterationInput.set("item", input.path("items").get(index));
                iterationInput.set("parent", input.path("parent"));
                iterationInput.set("previous", results.isEmpty() ? json.nullNode() : results.get(results.size() - 1));
                saved = WorkflowTraversal.initial(graph, id, identity.versionId(), null, null, values.write(iterationInput));
            }
            var child = new WorkflowTraversal(graph, saved, values, clock);
            return execute.apply(childIdentity, child).then(Mono.defer(() -> {
                if (child.waiting()) {
                    context.suspend(identity, parent, node, List.of(), null);
                    return Mono.empty();
                }
                if (!child.snapshot().status().equals("completed")) {
                    return Mono.error(new ApiException(HttpStatus.CONFLICT, "WORKFLOW_LOOP_INCOMPLETE", "循环中的步骤没有完成。"));
                }
                results.add(values.read(child.node(graph.endId()).output()));
                if (values.write(results).getBytes(StandardCharsets.UTF_8).length > WorkflowExpressions.MAX_VALUE_BYTES) {
                    return Mono.error(new ApiException(HttpStatus.CONFLICT, "WORKFLOW_OUTPUT_TOO_LARGE",
                        "循环结果过大，请减少处理条目或只保留所需字段。"));
                }
                return iteration(context, identity, parent, node, input, execute, index + 1, results);
            }));
        });
    }
}
