package com.stonewu.agenteam.service.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.plugin.entity.BuiltinExecutionContext;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 复用内置插件事务与工具操作编号，内部动作和工具结果不会分开提交。 */
@Service
public class CollaborationToolService {
    private final EnterpriseMapper enterprises;
    private final ToolCallMapper calls;
    private final CollaborationRunAccessService access;
    private final CollaborationActionService actions;
    private final CollaborationWorkService works;
    private final CollaborationArtifactService artifacts;
    private final CollaborationResultService results;
    private final CollaborationToolQueries queries;
    private final ResourceJson json;

    public CollaborationToolService(EnterpriseMapper enterprises, ToolCallMapper calls, CollaborationRunAccessService access,
            CollaborationActionService actions, CollaborationWorkService works, CollaborationArtifactService artifacts,
            CollaborationResultService results, CollaborationToolQueries queries, ResourceJson json) {
        this.enterprises = enterprises;
        this.calls = calls;
        this.access = access;
        this.actions = actions;
        this.works = works;
        this.artifacts = artifacts;
        this.results = results;
        this.queries = queries;
        this.json = json;
    }

    @Transactional
    public JsonNode call(BuiltinExecutionContext invocation, String name, JsonNode args) {
        enterprises.lockEnterprise(invocation.actor().enterpriseId()).orElseThrow();
        var call = calls.forRun(invocation.run().enterpriseId(), invocation.run().id()).stream()
            .filter(value -> value.operationId().equals(invocation.operationId()) && value.toolName().equals(name)).findFirst()
            .orElseThrow(CollaborationRunAccessService::contextRequired);
        boolean write = CollaborationToolDefinitions.WRITES.contains(name);
        var context = access.require(invocation.run(), call.frameworkSessionId(), false);
        if (!write) {
            return switch (name) {
                case "collab_list_roles" -> queries.roles(context, args);
                case "collab_read_context" -> queries.context(context, args);
                case "collab_read_artifact" -> artifacts.read(context.task(), CollaborationWorkService.text(args, "artifactId", 100),
                    args.path("version").asInt(), args.path("offset").asInt(0), args.path("limit").asInt(8000));
                default -> throw ApiException.invalidField("tool", "当前协作插件未提供此工具。");
            };
        }
        String operation = "tool:" + invocation.operationId();
        var replay = actions.replay(context.task().getEnterpriseId(), context.task().getId(), operation, args);
        if (replay.isPresent()) {
            return replay.get();
        }
        context = access.require(invocation.run(), call.frameworkSessionId(), true);
        if (!args.path("expectedInputRevision").isIntegralNumber() || args.path("expectedInputRevision").asLong() != context.task().getInputRevision()) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_INPUT_CHANGED", "任务要求已更新，请读取当前要求后继续。");
        }
        var action = actions.begin(context.task(), context.work().getId(), invocation.run().id(), call.id(), operation,
            name.substring("collab_".length()), args);
        Object result = switch (name) {
            case "collab_assign" -> works.assign(context, action, args, false);
            case "collab_handoff" -> works.assign(context, action, args, true);
            case "collab_wait" -> works.waitFor(context, action, args);
            case "collab_ask_user" -> works.ask(context, action, args);
            case "collab_submit_result" -> results.submit(context, action, args, false);
            case "collab_complete_task" -> results.submit(context, action, args, true);
            case "collab_publish_artifact" -> artifacts.describe(artifacts.publish(context.task(), context.work(), invocation.run().id(),
                CollaborationWorkService.text(args, "artifactKey", 100), CollaborationWorkService.text(args, "title", 160),
                CollaborationWorkService.text(args, "summary", 2000), CollaborationWorkService.text(args, "kind", 20),
                args.get("content"), args.path("fileId").asText(null), args.path("schemaName").asText(null)));
            default -> throw ApiException.invalidField("tool", "当前协作插件未提供此工具。");
        };
        return actions.finish(action, json.tree(result));
    }
}
