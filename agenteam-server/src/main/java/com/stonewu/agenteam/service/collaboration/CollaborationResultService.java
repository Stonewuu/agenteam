package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationExecutionContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 总任务完成以成果持久化为准，发送失败不重跑智能体。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationResultService {
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper workRows;
    private final CollaborationParticipantMapper participants;
    private final CollaborationWorkService works;
    private final CollaborationDependencyService dependencies;
    private final CollaborationArtifactService artifacts;
    private final ResourceJson json;
    private final Clock clock;
    private final ChatOutboundService outbound;

    public CollaborationResultService(CollaborationTaskMapper tasks, CollaborationWorkItemMapper workRows,
            CollaborationParticipantMapper participants, CollaborationWorkService works, CollaborationDependencyService dependencies,
            CollaborationArtifactService artifacts, ResourceJson json, Clock clock, ChatOutboundService outbound) {
        this.tasks = tasks;
        this.workRows = workRows;
        this.participants = participants;
        this.works = works;
        this.dependencies = dependencies;
        this.artifacts = artifacts;
        this.json = json;
        this.clock = clock;
        this.outbound = outbound;
    }

    public Object submit(CollaborationExecutionContext context, CollaborationActionRow action, JsonNode input, boolean complete) {
        return submit(context, action, input, complete, null);
    }

    public Object submit(CollaborationExecutionContext context, CollaborationActionRow action, JsonNode input, boolean complete, String replyText) {
        String summary = CollaborationWorkService.text(input, "summary", 2000);
        artifacts.references(context.task(), input.path("artifactRefs"));
        if (complete) {
            CollaborationWorkService.requireOwner(context.task(), context.work());
            requireComplete(context, input.path("acceptedPriorResults"));
        }
        var result = artifacts.publish(context.task(), context.work(), context.link().getRunId(), "result:" + context.work().getId(),
            context.participant().getRoleName() + "工作成果", summary, "json", input, null, "collaboration_result_v1");
        var work = context.work();
        if (workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, work.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getId, work.getId()).eq(CollaborationWorkItemRow::getRevision, work.getRevision())
            .set(CollaborationWorkItemRow::getResultArtifactId, result.getId())) != 1) {
            throw ApiException.versionConflict(work.getRevision());
        }
        boolean owner = context.task().getOwnerWorkId().equals(work.getId());
        if (complete) {
            works.change(work, "completed");
            finishTask(context, action, result.getId(), summary, input.path("artifactRefs"), replyText);
        } else if (!owner) {
            works.change(work, "completed");
            works.notify(context, action, "work_completed", "任务 " + context.task().getShortCode() + "：“" + context.participant().getRoleName() + "”已提交协助成果。");
        } else {
            var required = unfinished(context.task(), work.getId());
            if (required.isEmpty() && missingRoles(context.task()).isEmpty()) {
                requirePriorResults(context, input.path("acceptedPriorResults"));
                works.change(work, "completed");
                finishTask(context, action, result.getId(), summary, input.path("artifactRefs"), replyText);
            } else if (!required.isEmpty()) {
                dependencies.add(context.task(), work.getId(), required.stream().map(CollaborationWorkItemRow::getId).toList(), "resume");
                works.change(work, dependencies.readiness(context.task(), work.getId()).equals("blocked") ? "blocked" : "waiting_dependency");
            } else {
                boolean alreadyCorrected = work.getResumeReason().equals("role_assignment_missing");
                works.change(work, alreadyCorrected ? "waiting_input" : "ready");
                workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, work.getEnterpriseId())
                    .eq(CollaborationWorkItemRow::getId, work.getId()).set(CollaborationWorkItemRow::getResumeReason, "role_assignment_missing"));
                if (alreadyCorrected) {
                    tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, context.task().getEnterpriseId())
                        .eq(CollaborationTaskRow::getId, context.task().getId()).set(CollaborationTaskRow::getStatus, "waiting_input")
                        .set(CollaborationTaskRow::getErrorCode, "COLLABORATION_ROLE_ASSIGNMENT_REQUIRED").setIncrBy(CollaborationTaskRow::getRevision, 1));
                    works.notify(context, action, "waiting_input", "任务 " + context.task().getShortCode() + " 的部分指定岗位尚未分工，请补充明确分工后继续。");
                }
            }
        }
        return Map.of("taskId", context.task().getId(), "workItemId", work.getId(), "status", work.getStatus(), "artifact", artifacts.describe(result));
    }

    private void requireComplete(CollaborationExecutionContext context, JsonNode accepted) {
        if (!missingRoles(context.task()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_ROLE_ASSIGNMENT_REQUIRED", "原发起人明确指定的岗位还没有安排工作，不能结束任务。");
        }
        if (!unfinished(context.task(), context.work().getId()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_REQUIRED_WORK_PENDING", "仍有必须完成的协作工作，请等待或替换失败工作。");
        }
        requirePriorResults(context, accepted);
    }

    public List<CollaborationWorkItemRow> unfinished(CollaborationTaskRow task, String owner) {
        return dependencies.works(task).stream().filter(work -> Boolean.TRUE.equals(work.getRequired()) && !work.getId().equals(owner)
            && !work.getStatus().equals("superseded") && (!Set.of("completed", "handed_off").contains(work.getStatus()) || work.getActiveRunId() != null)).toList();
    }

    public List<String> missingRoles(CollaborationTaskRow task) {
        var assigned = dependencies.works(task).stream().filter(work -> !work.getStatus().equals("cancelled") && !work.getStatus().equals("superseded"))
            .map(CollaborationWorkItemRow::getParticipantId).collect(Collectors.toSet());
        var waived = new HashSet<String>();
        json.read(task.getWaivedRequestedRolesJson()).forEach(id -> waived.add(id.asText()));
        return participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationParticipantRow::getTaskId, task.getId()).eq(CollaborationParticipantRow::getExplicitlyRequested, true)).stream()
            .map(CollaborationParticipantRow::getId).filter(id -> !assigned.contains(id) && !waived.contains(id)).toList();
    }

    private void requirePriorResults(CollaborationExecutionContext context, JsonNode accepted) {
        var task = context.task();
        for (var work : dependencies.works(task)) {
            if (!Boolean.TRUE.equals(work.getRequired()) || work.getId().equals(context.work().getId()) || !work.getStatus().equals("completed")
                || work.getInputRevision().equals(task.getInputRevision())) {
                continue;
            }
            boolean confirmed = false;
            for (var entry : accepted) {
                if (work.getId().equals(entry.path("workItemId").asText()) && work.getResultArtifactId() != null
                    && work.getResultArtifactId().equals(entry.path("artifactId").asText()) && !entry.path("reason").asText().isBlank()) {
                    artifacts.require(task, entry.path("artifactId").asText(), entry.path("version").asInt());
                    confirmed = true;
                }
            }
            if (!confirmed) {
                throw new ApiException(HttpStatus.CONFLICT, "TASK_INPUT_CHANGED", "有成果依据旧要求生成，请逐项核对并说明仍可使用的理由，或安排新的工作。");
            }
        }
    }

    private void finishTask(CollaborationExecutionContext context, CollaborationActionRow action, String artifact, String summary,
            JsonNode references, String replyText) {
        var task = context.task();
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getRevision, task.getRevision())
            .eq(CollaborationTaskRow::getInputRevision, context.link().getInputRevision()).eq(CollaborationTaskRow::getOwnerWorkId, context.work().getId())
            .set(CollaborationTaskRow::getStatus, "completed").set(CollaborationTaskRow::getFinalArtifactId, artifact)
            .set(CollaborationTaskRow::getFinishedAt, clock.instant()).set(CollaborationTaskRow::getUpdatedAt, clock.instant())
            .setIncrBy(CollaborationTaskRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(task.getRevision());
        }
        workRows.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getRequired, false)
            .isNull(CollaborationWorkItemRow::getActiveRunId).in(CollaborationWorkItemRow::getStatus, "ready", "blocked", "waiting_input", "waiting_dependency")
            .set(CollaborationWorkItemRow::getStatus, "cancelled").setIncrBy(CollaborationWorkItemRow::getRevision, 1));
        // 排队时保存完整候选内容，是否向原会话发送由实际发送前的当前设置决定。
        String message = replyText == null || replyText.isBlank() ? summary : replyText;
        if (message.codePointCount(0, message.length()) > 32000) {
            message = "完整回复较长，已整理为文件，请查看附件。";
        }
        works.notify(context, action, "completed", message);
        var sentFiles = new HashSet<String>();
        for (var file : artifacts.references(task, references)) {
            if (file.getFileId() != null && sentFiles.add(file.getFileId())) {
                outbound.file(task.getEnterpriseId(), task.getConversationId(), context.participant().getBotBindingId(), task.getId(), action.getId(),
                    "final-file:" + action.getId() + ":" + file.getId(), file);
            }
        }
    }
}
