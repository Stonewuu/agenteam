package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolCallMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.http.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

/** 用户明确重试单项失败工作；未知业务写结果不能通过新一轮执行绕过。 */
@Service
public class CollaborationRetryService {
    private final EnterpriseMapper enterprises;
    private final CollaborationAccessService access;
    private final CollaborationWorkService workService;
    private final CollaborationWorkItemMapper works;
    private final CollaborationTaskMapper tasks;
    private final CollaborationRunLinkMapper links;
    private final RunMapper runs;
    private final ToolCallMapper calls;
    private final CollaborationActionService actions;
    private final ResourceJson json;
    private final Clock clock;

    public CollaborationRetryService(EnterpriseMapper enterprises, CollaborationAccessService access, CollaborationWorkService workService,
            CollaborationWorkItemMapper works, CollaborationTaskMapper tasks, CollaborationRunLinkMapper links, RunMapper runs,
            ToolCallMapper calls, CollaborationActionService actions, ResourceJson json, Clock clock) {
        this.enterprises = enterprises;
        this.access = access;
        this.workService = workService;
        this.works = works;
        this.tasks = tasks;
        this.links = links;
        this.runs = runs;
        this.calls = calls;
        this.actions = actions;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public JsonNode retry(AuthContext actor, String taskId, String workId, long expectedRevision, String operation) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var task = access.owned(actor, taskId);
        var work = workService.require(task, workId);
        access.requireRole(task, access.participant(task, work.getParticipantId()));
        var arguments = json.tree(Map.of("workItemId", workId));
        var replay = actions.replay(actor.enterpriseId(), taskId, operation, arguments);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (task.getRevision() != expectedRevision) {
            throw ApiException.versionConflict(task.getRevision());
        }
        if (!Set.of("active", "blocked", "waiting_input", "waiting_approval").contains(task.getStatus())
            || !task.getDeadlineAt().isAfter(clock.instant()) || work.getActiveRunId() != null
            || !Set.of("failed", "blocked", "cancelled").contains(work.getStatus()) || work.getReplacementWorkId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_RETRY_UNAVAILABLE", "此工作当前不能重试，请先等待执行结束或另起任务。");
        }
        if (task.getRunCount() >= json.read(task.getPolicySnapshot()).path("maxRuns").asInt(40)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_LIMIT_REACHED", "此任务已达到执行次数上限，请另起任务。");
        }
        boolean unknown = "TOOL_RESULT_UNKNOWN".equals(work.getErrorCode());
        for (var link : links.selectList(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRunLinkRow::getWorkId, workId))) {
            var run = runs.find(actor.enterpriseId(), link.getRunId(), false).orElseThrow();
            unknown |= "TOOL_RESULT_UNKNOWN".equals(run.errorCode()) || calls.forRun(actor.enterpriseId(), run.id()).stream()
                .anyMatch(call -> !call.readOnly() && (call.status().equals("unknown") || call.status().equals("running") && call.submittedAt() != null));
        }
        if (unknown) {
            throw new ApiException(HttpStatus.CONFLICT, "TOOL_RESULT_UNKNOWN", "上一次业务操作的结果需要核实，不能重新执行此工作。");
        }
        var action = actions.begin(task, workId, null, null, operation, "work_retry", arguments);
        if (works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationWorkItemRow::getId, workId).eq(CollaborationWorkItemRow::getRevision, work.getRevision())
            .isNull(CollaborationWorkItemRow::getActiveRunId).set(CollaborationWorkItemRow::getStatus, "ready")
            .set(CollaborationWorkItemRow::getInputRevision, task.getInputRevision()).set(CollaborationWorkItemRow::getContextId, null)
            .set(CollaborationWorkItemRow::getResumeReason, "retry").set(CollaborationWorkItemRow::getErrorCode, null)
            .set(CollaborationWorkItemRow::getNextAttemptAt, null).set(CollaborationWorkItemRow::getLeaseOwner, null)
            .set(CollaborationWorkItemRow::getLeaseUntil, null).set(CollaborationWorkItemRow::getUpdatedAt, clock.instant())
            .setIncrBy(CollaborationWorkItemRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(work.getRevision());
        }
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getRevision, expectedRevision)
            .set(CollaborationTaskRow::getStatus, "active").set(CollaborationTaskRow::getErrorCode, null)
            .setIncrBy(CollaborationTaskRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(task.getRevision());
        }
        return actions.finish(action, Map.of("taskId", taskId, "workItemId", workId, "status", "ready"));
    }
}
