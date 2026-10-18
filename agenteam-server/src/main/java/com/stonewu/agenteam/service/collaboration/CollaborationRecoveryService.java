package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import com.stonewu.agenteam.configuration.chat.ChatConfiguration;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;

/** 持续复核当前授权与任务期限，把取消请求交给既有执行器处理。 */
@Service
public class CollaborationRecoveryService {
    private static final Logger LOG = LoggerFactory.getLogger(CollaborationRecoveryService.class);
    private final CollaborationRunLinkMapper links;
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper works;
    private final EnterpriseMapper enterprises;
    private final RunMapper runs;
    private final CollaborationAccessService access;
    private final ChatOutboundService outbound;
    private final Clock clock;
    private final ChatConfiguration chat;
    private final CollaborationConfiguration configuration;

    public CollaborationRecoveryService(CollaborationRunLinkMapper links, CollaborationTaskMapper tasks,
            CollaborationWorkItemMapper works, EnterpriseMapper enterprises, RunMapper runs,
            CollaborationAccessService access, ChatOutboundService outbound, Clock clock, ChatConfiguration chat, CollaborationConfiguration configuration) {
        this.links = links;
        this.tasks = tasks;
        this.works = works;
        this.enterprises = enterprises;
        this.runs = runs;
        this.access = access;
        this.outbound = outbound;
        this.clock = clock;
        this.chat = chat;
        this.configuration = configuration;
    }

    @Transactional
    public Optional<StopRequest> recheck(String enterprise, String linkId) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getId, linkId).isNull(CollaborationRunLinkRow::getTerminalObservedAt));
        if (link == null) {
            return Optional.empty();
        }
        var run = runs.find(enterprise, link.getRunId(), false).orElseThrow();
        if (run.terminal()) {
            return Optional.empty();
        }
        var task = access.require(enterprise, link.getTaskId());
        String reason = link.getRequestedStopReason();
        if (reason == null && (!chat.isEnabled() || !configuration.isEnabled())) {
            reason = "feature_disabled";
        }
        if (reason == null && task.getStatus().equals("completed")) {
            reason = "task_completed";
        }
        if (reason == null && !task.getDeadlineAt().isAfter(clock.instant())) {
            reason = "deadline";
        }
        if (reason == null && (task.getStatus().equals("cancelling") || task.getStatus().equals("cancelled"))) {
            reason = "user_cancel";
        }
        if (reason == null && !task.getInputRevision().equals(link.getInputRevision())) {
            reason = "input_changed";
        }
        if (reason == null) {
            try {
                access.requireRole(task, access.participant(task, link.getParticipantId()));
            } catch (ApiException revoked) {
                LOG.warn("协作执行权限已失效，企业={}，任务={}，执行={}", enterprise, task.getId(), run.id(),
                    new ChannelProviderException("COLLABORATION_ACCESS_REVOKED", "原账号或岗位已不能继续执行。", revoked));
                reason = "access_revoked";
            }
        }
        if (reason == null) {
            return Optional.empty();
        }
        links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getId, linkId).set(CollaborationRunLinkRow::getRequestedStopReason, reason));
        return Optional.of(new StopRequest(enterprise, run.id(), task.getId(), reason));
    }

    public List<CollaborationTaskRow> ending() {
        return tasks.selectPage(new Page<CollaborationTaskRow>(1, 50, false), new LambdaQueryWrapper<CollaborationTaskRow>()
            .and(query -> query.eq(CollaborationTaskRow::getStatus, "cancelling").or(expired -> expired
                .in(CollaborationTaskRow::getStatus, "active", "blocked", "waiting_input", "waiting_approval")
                .le(CollaborationTaskRow::getDeadlineAt, clock.instant())))
            .orderByAsc(CollaborationTaskRow::getUpdatedAt, CollaborationTaskRow::getId)).getRecords();
    }

    @Transactional
    public void settle(String enterprise, String taskId) {
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var task = access.require(enterprise, taskId);
        boolean expired = !task.getDeadlineAt().isAfter(clock.instant());
        if (!task.getStatus().equals("cancelling") && !expired || task.getStatus().equals("completed") || task.getStatus().equals("cancelled") || task.getStatus().equals("failed")) {
            return;
        }
        works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, enterprise)
            .eq(CollaborationWorkItemRow::getTaskId, taskId).isNull(CollaborationWorkItemRow::getActiveRunId)
            .in(CollaborationWorkItemRow::getStatus, "ready", "blocked", "waiting_input", "waiting_dependency", "waiting_approval")
            .set(CollaborationWorkItemRow::getStatus, "cancelled").set(CollaborationWorkItemRow::getLeaseOwner, null)
            .set(CollaborationWorkItemRow::getLeaseUntil, null).setIncrBy(CollaborationWorkItemRow::getRevision, 1));
        links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getTaskId, taskId).isNull(CollaborationRunLinkRow::getTerminalObservedAt)
            .set(CollaborationRunLinkRow::getRequestedStopReason, expired ? "deadline" : "user_cancel"));
        long active = links.selectCount(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, enterprise)
            .eq(CollaborationRunLinkRow::getTaskId, taskId).isNull(CollaborationRunLinkRow::getTerminalObservedAt));
        String status = active > 0 ? "cancelling" : expired ? "failed" : "cancelled";
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, enterprise)
            .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getRevision, task.getRevision())
            .set(CollaborationTaskRow::getStatus, status).set(expired, CollaborationTaskRow::getErrorCode, "COLLABORATION_DEADLINE_EXCEEDED")
            .set(active == 0, CollaborationTaskRow::getFinishedAt, clock.instant()).set(CollaborationTaskRow::getUpdatedAt, clock.instant())
            .setIncrBy(CollaborationTaskRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(task.getRevision());
        }
        if (active == 0) {
            outbound.text(enterprise, task.getConversationId(), task.getEntryBotId(), taskId, null, "task-ended:" + taskId, "cancelled",
                "任务 【" + task.getShortCode() + "】：" + (expired ? "已到截止时间，未继续执行。已保存的成果仍可在群策查看。" : "已取消。已完成的业务操作与成果保留。")
                    + ("TOOL_RESULT_UNKNOWN".equals(task.getErrorCode()) ? "仍有操作结果尚未确认，请先在群策核实。" : ""), null);
        }
    }

    public record StopRequest(String enterpriseId, String runId, String taskId, String reason) {
    }
}
