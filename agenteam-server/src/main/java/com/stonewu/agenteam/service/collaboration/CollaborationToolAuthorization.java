package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.mapper.collaboration.CollaborationToolDefinitions;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.model.tool.entity.ToolCallRecord;
import org.springframework.stereotype.Service;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;

/** 只对固定内置协作动作免逐次确认，其他业务写入继续沿用原审批策略。 */
@Service
public class CollaborationToolAuthorization {
    private final CollaborationRunAccessService access;
    private final CollaborationToolDefinitions definitions;

    public CollaborationToolAuthorization(CollaborationRunAccessService access, CollaborationToolDefinitions definitions) {
        this.access = access;
        this.definitions = definitions;
    }

    public boolean isInternalAction(RunRecord run, ExecutionToolBinding binding, ToolCallRecord call) {
        if (call == null || !access.applies(run) || binding.resourceVersionId() == null || !binding.matches(call)
            || !call.runId().equals(run.id()) || !call.actorUserId().equals(run.userId())
            || !run.conversationId().equals(call.frameworkSessionId()) || !"builtin".equals(binding.config().path("pluginType").asText())
            || !"group_collaboration".equals(binding.config().path("builtinCode").asText())
            || !CollaborationToolDefinitions.WRITES.contains(call.toolName())
            || definitions.tools().stream().noneMatch(tool -> tool.name().equals(call.toolName()) && tool.schemaHash().equals(binding.definition().schemaHash()))) {
            return false;
        }
        access.require(run, call.frameworkSessionId(), call.resultEncrypted() == null);
        return true;
    }

    public void requireWrite(RunRecord run, ExecutionToolBinding binding, ToolCallRecord call) {
        if (access.applies(run) && !call.readOnly()) {
            access.require(run, run.conversationId(), true);
            if (run.executionConfig().at("/collaboration/planningOnly").asBoolean() && !isInternalAction(run, binding, call)) {
                throw new ApiException(HttpStatus.CONFLICT, "TASK_REPLANNING_REQUIRED", "本轮只允许核对新要求和重新安排工作，不能执行业务写入。");
            }
        }
    }
}
