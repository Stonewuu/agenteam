package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationContextMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.ToolApprovalPolicy;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationContextRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkLease;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.project.ProjectMetadataService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/** 执行和岗位关联同事务保存，同一任务的独立岗位会话共用项目文件。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationSubmissionRecords {
    private final CollaborationWorkItemMapper works;
    private final CollaborationContextMapper contexts;
    private final CollaborationParticipantMapper participants;
    private final CollaborationRunLinkMapper links;
    private final CollaborationTaskMapper tasks;
    private final ConversationSqlMapper conversations;
    private final RunMapper runs;
    private final CollaborationAccessService access;
    private final ProjectMetadataService projects;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final Clock clock;

    public CollaborationSubmissionRecords(CollaborationWorkItemMapper works, CollaborationContextMapper contexts,
            CollaborationParticipantMapper participants, CollaborationRunLinkMapper links, CollaborationTaskMapper tasks,
            ConversationSqlMapper conversations, RunMapper runs, CollaborationAccessService access,
            ProjectMetadataService projects, ChatPayloadCipher cipher, ResourceJson json, Clock clock) {
        this.works = works;
        this.contexts = contexts;
        this.participants = participants;
        this.links = links;
        this.tasks = tasks;
        this.conversations = conversations;
        this.runs = runs;
        this.access = access;
        this.projects = projects;
        this.cipher = cipher;
        this.json = json;
        this.clock = clock;
    }

    public Optional<RunAccepted> replay(AuthContext actor, CollaborationWorkLease lease, int round) {
        var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRunLinkRow::getWorkId, lease.workId()).eq(CollaborationRunLinkRow::getRunRound, round));
        if (link == null) {
            return Optional.empty();
        }
        access.owned(actor, link.getTaskId());
        var run = runs.find(actor.enterpriseId(), link.getRunId(), false).orElseThrow();
        return Optional.of(new RunAccepted(run.conversationId(), run.id(), run.inputMessageId(), run.outputMessageId(), run.status(), Long.toString(run.lastSequence())));
    }

    public void requireWebConversation(AuthContext actor, String conversation) {
        var row = conversations.selectOne(new LambdaQueryWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId())
            .eq(AgentConversationRow::getUserId, actor.userId()).eq(AgentConversationRow::getId, conversation));
        if (row != null && "collaboration".equals(row.getSourceType())) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_INPUT_REQUIRED", "请在协作任务详情中补充要求，岗位会话由任务统一安排。");
        }
    }

    public Prepared prepare(AuthContext actor, CollaborationWorkLease lease, String contextId, int round) {
        var work = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationWorkItemRow::getId, lease.workId()).eq(CollaborationWorkItemRow::getLeaseOwner, lease.owner())
            .eq(CollaborationWorkItemRow::getLeaseVersion, lease.version()).gt(CollaborationWorkItemRow::getLeaseUntil, clock.instant()));
        if (work == null || !actor.enterpriseId().equals(lease.enterpriseId()) || !work.getStatus().equals("ready")
            || work.getActiveRunId() != null || round != work.getRunRound() + 1) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_WORK_LEASE_EXPIRED", "岗位工作已被接管或已经开始。");
        }
        var task = access.owned(actor, work.getTaskId());
        var role = access.participant(task, work.getParticipantId());
        access.requireRole(task, role);
        var policy = json.read(task.getPolicySnapshot());
        if (!Set.of("active", "waiting_input", "waiting_approval", "blocked").contains(task.getStatus()) || !task.getDeadlineAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_STOPPED", "任务已停止或到达截止时间。");
        }
        if (task.getRunCount() >= policy.path("maxRuns").asInt(40)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_LIMIT_REACHED", "任务已达到允许的执行次数。");
        }
        long active = works.selectCount(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).isNotNull(CollaborationWorkItemRow::getActiveRunId));
        long sameRole = works.selectCount(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getParticipantId, role.getId())
            .isNotNull(CollaborationWorkItemRow::getActiveRunId));
        if (active >= policy.path("maxParallel").asInt(2) || sameRole > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENCY_LIMIT", "正在等待岗位或任务的可用执行名额。");
        }
        var context = contexts.selectOne(new LambdaQueryWrapper<CollaborationContextRow>().eq(CollaborationContextRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationContextRow::getId, contextId).eq(CollaborationContextRow::getTaskId, task.getId())
            .eq(CollaborationContextRow::getWorkId, work.getId()).eq(CollaborationContextRow::getRunRound, round)
            .eq(CollaborationContextRow::getInputRevision, task.getInputRevision()));
        if (context == null || !work.getInputRevision().equals(task.getInputRevision())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_INPUT_CHANGED", "要求或上下文已变化，需要重新准备。");
        }
        if (role.getProjectId() == null) {
            var projectIds = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
                .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationParticipantRow::getTaskId, task.getId()).isNotNull(CollaborationParticipantRow::getProjectId))
                .stream().map(CollaborationParticipantRow::getProjectId).distinct().toList();
            if (projectIds.size() > 1) {
                throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_LEGACY_WORKSPACE",
                    "此旧任务的岗位文件分散在不同项目，请保留原文件并新建协作任务。");
            }
            String projectId = projectIds.isEmpty()
                ? projects.createOwned(actor.enterpriseId(), actor.userId(), task.getShortCode() + " 协作任务", null, null).getId()
                : projects.require(actor.enterpriseId(), actor.userId(), projectIds.getFirst()).getId();
            if (participants.update(new LambdaUpdateWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationParticipantRow::getTaskId, task.getId()).isNull(CollaborationParticipantRow::getProjectId)
                .set(CollaborationParticipantRow::getProjectId, projectId)) < 1) {
                throw new IllegalStateException("协作项目已由其他工作建立");
            }
            role.setProjectId(projectId);
        }
        var body = cipher.decrypt(task.getEnterpriseId(), "context", context.getId(), context.getContentEncrypted());
        if (!json.hash(body).equals(context.getContentHash())) {
            throw new IllegalStateException("岗位上下文的内容校验失败");
        }
        return new Prepared(task, work, role, context, body.toString());
    }

    public void saved(Prepared prepared, CollaborationWorkLease lease, RunAccepted run) {
        var task = prepared.task();
        var work = prepared.work();
        var role = prepared.participant();
        if (conversations.update(new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, task.getEnterpriseId())
            .eq(AgentConversationRow::getId, run.conversationId()).eq(AgentConversationRow::getUserId, task.getInitiatorUserId())
            .set(AgentConversationRow::getSourceType, "collaboration").set(AgentConversationRow::getSourceId, role.getId())) != 1) {
            throw new IllegalStateException("岗位会话来源无法保存");
        }
        if (participants.update(new LambdaUpdateWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationParticipantRow::getId, role.getId()).set(CollaborationParticipantRow::getConversationId, run.conversationId())) != 1) {
            throw new IllegalStateException("岗位会话无法保存");
        }
        var link = ChatRecords.initialize(new CollaborationRunLinkRow(), task.getEnterpriseId(), clock.instant());
        link.setTaskId(task.getId());
        link.setWorkId(work.getId());
        link.setParticipantId(role.getId());
        link.setRunRound(prepared.context().getRunRound());
        link.setRunId(run.runId());
        link.setContextId(prepared.context().getId());
        link.setInputRevision(task.getInputRevision());
        link.setSubmissionKey(work.getId() + ":" + link.getRunRound());
        link.setLastStatus("queued");
        links.insert(link);
        if (works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getId, work.getId()).eq(CollaborationWorkItemRow::getLeaseOwner, lease.owner())
            .eq(CollaborationWorkItemRow::getLeaseVersion, lease.version()).eq(CollaborationWorkItemRow::getStatus, "ready")
            .isNull(CollaborationWorkItemRow::getActiveRunId).set(CollaborationWorkItemRow::getStatus, "running")
            .set(CollaborationWorkItemRow::getActiveRunId, run.runId()).set(CollaborationWorkItemRow::getContextId, link.getContextId())
            .set(CollaborationWorkItemRow::getRunRound, link.getRunRound()).set(CollaborationWorkItemRow::getLeaseOwner, null)
            .set(CollaborationWorkItemRow::getLeaseUntil, null).setIncrBy(CollaborationWorkItemRow::getRevision, 1)) != 1) {
            throw new IllegalStateException("岗位工作已被其他进程接管");
        }
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getRevision, task.getRevision())
            .setIncrBy(CollaborationTaskRow::getRunCount, 1).setIncrBy(CollaborationTaskRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(task.getRevision());
        }
    }

    public String displayText(Prepared prepared) {
        return CollaborationMessageDisplayService.renderLatestInput(json.read(prepared.text()).at("/task/requirements"), Map.of());
    }

    public String approvalPolicy(Prepared prepared) {
        return ToolApprovalPolicy.from(json.read(prepared.task().getPolicySnapshot())
            .path("approvalPolicy").asText(ToolApprovalPolicy.DEFAULT.value())).value();
    }

    public boolean planningOnly(Prepared prepared) {
        return prepared.work().getId().equals(prepared.task().getOwnerWorkId())
            && prepared.work().getResumeReason().equals("input_changed")
            && works.selectCount(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, prepared.task().getEnterpriseId())
                .eq(CollaborationWorkItemRow::getTaskId, prepared.task().getId()).ne(CollaborationWorkItemRow::getId, prepared.work().getId())
                .eq(CollaborationWorkItemRow::getRequired, true).lt(CollaborationWorkItemRow::getInputRevision, prepared.task().getInputRevision())
                .notIn(CollaborationWorkItemRow::getStatus, "completed", "handed_off", "superseded")) > 0;
    }

    public record Prepared(CollaborationTaskRow task, CollaborationWorkItemRow work, CollaborationParticipantRow participant,
                           CollaborationContextRow context, String text) {
    }
}
