package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationActionMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationExecutionContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Set;

/** 顶层岗位工作和原发起人必须同时匹配，交接后不能继续发起业务写操作。 */
@Service
public class CollaborationRunAccessService {
    private final CollaborationRunLinkMapper links;
    private final CollaborationWorkItemMapper works;
    private final CollaborationAccessService access;
    private final Clock clock;
    private final CollaborationActionMapper actions;

    public CollaborationRunAccessService(CollaborationRunLinkMapper links, CollaborationWorkItemMapper works,
            CollaborationAccessService access, Clock clock,
            CollaborationActionMapper actions) {
        this.links = links;
        this.works = works;
        this.access = access;
        this.clock = clock;
        this.actions = actions;
    }

    public boolean applies(RunRecord run) {
        return "collaboration".equals(run.executionConfig().path("sourceType").asText());
    }

    /** 只有本轮已提交的结束动作才是正常收尾；撤权、取消和要求变更仍由原检查拒绝。 */
    public boolean hasCommittedRoundEnd(RunRecord run) {
        if (!applies(run)) {
            return false;
        }
        var context = require(run, run.conversationId(), false);
        if (!Set.of("handed_off", "waiting_input", "waiting_dependency", "completed", "blocked", "ready").contains(context.work().getStatus())) {
            return false;
        }
        return actions.exists(new LambdaQueryWrapper<CollaborationActionRow>()
            .eq(CollaborationActionRow::getEnterpriseId, run.enterpriseId()).eq(CollaborationActionRow::getTaskId, context.task().getId())
            .eq(CollaborationActionRow::getWorkId, context.work().getId()).eq(CollaborationActionRow::getSourceRunId, run.id())
            .eq(CollaborationActionRow::getInputRevision, context.link().getInputRevision()).isNotNull(CollaborationActionRow::getResultEncrypted)
            .in(CollaborationActionRow::getActionType, "ask_user", "handoff", "wait", "submit_result", "complete_task"));
    }

    public CollaborationExecutionContext require(RunRecord run, String session, boolean write) {
        if (!applies(run) || !run.mode().equals("interactive") || !run.conversationId().equals(session)) {
            throw contextRequired();
        }
        var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, run.enterpriseId())
            .eq(CollaborationRunLinkRow::getRunId, run.id()));
        if (link == null) {
            throw contextRequired();
        }
        var task = access.require(run.enterpriseId(), link.getTaskId());
        var participant = access.participant(task, link.getParticipantId());
        var work = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, run.enterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getId, link.getWorkId())
            .eq(CollaborationWorkItemRow::getParticipantId, participant.getId()));
        if (work == null || !run.userId().equals(task.getInitiatorUserId()) || !run.conversationId().equals(participant.getConversationId())
            || !run.agentVersionId().equals(participant.getAgentVersionId()) || !run.id().equals(work.getActiveRunId())
            || link.getTerminalObservedAt() != null) {
            throw contextRequired();
        }
        var actor = access.requireRole(task, participant);
        if (Set.of("cancelling", "cancelled", "failed").contains(task.getStatus()) || !task.getDeadlineAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_STOPPED", "协作任务已经停止或到达截止时间。");
        }
        if (!task.getInputRevision().equals(link.getInputRevision()) || !work.getInputRevision().equals(link.getInputRevision())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_INPUT_CHANGED", "任务要求已更新，需要按新要求继续。");
        }
        boolean running = work.getStatus().equals("running") || work.getStatus().equals("waiting_approval") && run.status().equals("running");
        if (write && (!running || task.getStatus().equals("completed"))) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_WORK_FINISHED", "本轮岗位工作已提交、移交或等待，不能继续修改业务。");
        }
        return new CollaborationExecutionContext(actor, task, work, participant, link);
    }

    public static ApiException contextRequired() {
        return new ApiException(HttpStatus.CONFLICT, "COLLABORATION_CONTEXT_REQUIRED", "此操作只能在当前任务的顶层岗位工作中使用。");
    }
}
