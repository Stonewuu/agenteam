package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationExecutionContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 派活、移交和等待只修改内部工作，不靠平台重新推送机器人消息触发。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationWorkService {
    private final CollaborationWorkItemMapper works;
    private final CollaborationTaskMapper tasks;
    private final CollaborationAccessService access;
    private final CollaborationDependencyService dependencies;
    private final CollaborationArtifactService artifacts;
    private final ChatPayloadCipher cipher;
    private final ChatOutboundService outbound;
    private final ResourceJson json;
    private final Clock clock;
    private final CollaborationQuestionNoticeService questions;

    public CollaborationWorkService(CollaborationWorkItemMapper works, CollaborationTaskMapper tasks, CollaborationAccessService access,
            CollaborationDependencyService dependencies, CollaborationArtifactService artifacts, ChatPayloadCipher cipher,
            ChatOutboundService outbound, ResourceJson json, Clock clock, CollaborationQuestionNoticeService questions) {
        this.works = works;
        this.tasks = tasks;
        this.access = access;
        this.dependencies = dependencies;
        this.artifacts = artifacts;
        this.cipher = cipher;
        this.outbound = outbound;
        this.json = json;
        this.clock = clock;
        this.questions = questions;
    }

    public Object assign(CollaborationExecutionContext context, CollaborationActionRow action, JsonNode input, boolean handoff) {
        var task = context.task();
        var source = context.work();
        if (handoff) {
            requireOwner(task, source);
        }
        String targetId = text(input, "targetRoleId", 100);
        var target = access.participant(task, targetId);
        access.requireRole(task, target);
        for (var waived : json.read(task.getWaivedRequestedRolesJson())) {
            if (waived.asText().equals(targetId)) {
                throw ApiException.invalidField("targetRoleId", "原发起人已取消此岗位，不能再次安排工作。");
            }
        }
        if (targetId.equals(source.getParticipantId())) {
            throw ApiException.invalidField("targetRoleId", "请把协助或移交工作交给另一个岗位。");
        }
        var policy = json.read(task.getPolicySnapshot());
        if (handoff && repeatedHandoffs(task, source.getParticipantId(), targetId) >= 3) {
            var result = ask(context, action, json.tree(Map.of("question", "这两个岗位已经多次互相移交，请明确下一步由谁负责以及需要补充的成果。")));
            tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
                .eq(CollaborationTaskRow::getId, task.getId()).set(CollaborationTaskRow::getErrorCode, "COLLABORATION_HANDOFF_CLARIFICATION"));
            return result;
        }
        if (task.getWorkCount() >= policy.path("maxWorks").asInt(20) || handoff && task.getHandoffCount() >= policy.path("maxHandoffs").asInt(8)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_LIMIT_REACHED", "已达到当前任务允许的工作或交接次数。");
        }
        String goal = text(input, handoff ? "nextGoal" : "goal", 4000);
        text(input, "acceptanceCriteria", 2000);
        var references = artifacts.references(task, input.path("artifactRefs"));
        if (handoff) {
            text(input, "completedSummary", 2000);
            if (references.isEmpty()) {
                throw ApiException.invalidField("artifactRefs", "移交前请发布已完成内容，并携带至少一份成果。");
            }
        }
        var next = ChatRecords.initialize(new CollaborationWorkItemRow(), task.getEnterpriseId(), clock.instant());
        next.setTaskId(task.getId());
        next.setParticipantId(targetId);
        next.setRequesterWorkId(source.getId());
        next.setOriginActionId(action.getId());
        next.setPurpose(handoff ? "continuation" : "assist");
        next.setRequired(handoff || Boolean.TRUE.equals(target.getExplicitlyRequested()) || input.path("required").asBoolean(true));
        next.setInstructionEncrypted(cipher.encrypt(task.getEnterpriseId(), "work-instruction", next.getId(), input));
        next.setStatus("ready");
        next.setResumeReason("initial");
        next.setInputRevision(task.getInputRevision());
        next.setRunRound(0);
        next.setDeadlineAt(task.getDeadlineAt());
        next.setLeaseVersion(0L);
        String replacedId = input.path("replacesWorkItemId").asText(null);
        CollaborationWorkItemRow previous = null;
        if (replacedId != null) {
            requireOwner(task, source);
            previous = require(task, replacedId);
            next.setRequired(previous.getRequired());
        }
        works.insert(next);
        dependencies.add(task, next.getId(), identifiers(input, "dependsOnWorkIds"), "start");
        if (previous != null) {
            dependencies.replace(task, previous, next);
        }
        String status = dependencies.readiness(task, next.getId());
        change(next, status);
        var update = new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getRevision, task.getRevision())
            .setIncrBy(CollaborationTaskRow::getWorkCount, 1).setIncrBy(CollaborationTaskRow::getRevision, 1)
            .set(CollaborationTaskRow::getUpdatedAt, clock.instant());
        if (handoff) {
            change(source, "handed_off");
            update.set(CollaborationTaskRow::getOwnerParticipantId, targetId).set(CollaborationTaskRow::getOwnerWorkId, next.getId())
                .setIncrBy(CollaborationTaskRow::getHandoffCount, 1);
        }
        if (tasks.update(update) != 1) {
            throw ApiException.versionConflict(task.getRevision());
        }
        notify(context, action, handoff ? "handed_off" : "assigned", "任务 【" + task.getShortCode() + "】：【" + context.participant().getRoleName()
            + "】已" + (handoff ? "移交给【" : "安排【") + target.getRoleName() + "】" + (handoff ? "继续负责。" : "协助处理。"));
        return Map.of("taskId", task.getId(), "workItemId", next.getId(), "inputRevision", task.getInputRevision(),
            "status", status, "goal", goal, "ownerWorkItemId", handoff ? next.getId() : task.getOwnerWorkId());
    }

    public Object waitFor(CollaborationExecutionContext context, CollaborationActionRow action, JsonNode input) {
        var required = identifiers(input, "workItemIds");
        if (required.isEmpty()) {
            throw ApiException.invalidField("workItemIds", "请选择至少一项要等待的工作。");
        }
        text(input, "reason", 2000);
        dependencies.add(context.task(), context.work().getId(), required, "resume");
        String status = dependencies.readiness(context.task(), context.work().getId()).equals("blocked") ? "blocked" : "waiting_dependency";
        change(context.work(), status);
        notify(context, action, "waiting_dependency", "任务 【" + context.task().getShortCode() + "】：【" + context.participant().getRoleName() + "】正在等待协作成果。");
        return Map.of("workItemId", context.work().getId(), "status", status, "nextAction", "结束本轮回答，成果齐备后系统会继续此工作。");
    }

    private long repeatedHandoffs(CollaborationTaskRow task, String firstRole, String secondRole) {
        var history = dependencies.works(task);
        var byId = history.stream().collect(Collectors.toMap(CollaborationWorkItemRow::getId, value -> value));
        return history.stream().filter(work -> work.getInputRevision().equals(task.getInputRevision()) && work.getPurpose().equals("continuation"))
            .filter(work -> {
                var source = byId.get(work.getRequesterWorkId());
                return source != null && (source.getParticipantId().equals(firstRole) && work.getParticipantId().equals(secondRole)
                    || source.getParticipantId().equals(secondRole) && work.getParticipantId().equals(firstRole));
            }).count();
    }

    public Object ask(CollaborationExecutionContext context, CollaborationActionRow action, JsonNode input) {
        String question = text(input, "question", 2000);
        var options = input.path("options");
        if (!options.isMissingNode() && (!options.isArray() || options.size() > 5)) {
            throw ApiException.invalidField("options", "最多提供五个选项。");
        }
        for (var option : options) {
            if (!option.isTextual() || option.asText().isBlank() || option.asText().codePointCount(0, option.asText().length()) > 200) {
                throw ApiException.invalidField("options", "每个选项需要填写一至二百个字符。");
            }
        }
        change(context.work(), "waiting_input");
        var task = context.task();
        if (tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationTaskRow::getId, task.getId()).eq(CollaborationTaskRow::getRevision, task.getRevision())
            .set(CollaborationTaskRow::getStatus, "waiting_input").setIncrBy(CollaborationTaskRow::getRevision, 1)) != 1) {
            throw ApiException.versionConflict(task.getRevision());
        }
        questions.send(context, action, question, options);
        return Map.of("workItemId", context.work().getId(), "status", "waiting_input", "question", question,
            "nextAction", "结束本轮回答，等待原发起人补充。");
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public CollaborationWorkItemRow require(CollaborationTaskRow task, String id) {
        var row = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getId, id));
        if (row == null) {
            throw ApiException.invalidField("workItemId", "工作不属于当前任务。");
        }
        return row;
    }

    public void change(CollaborationWorkItemRow work, String status) {
        if (works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, work.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getId, work.getId()).eq(CollaborationWorkItemRow::getRevision, work.getRevision())
            .set(CollaborationWorkItemRow::getStatus, status).setIncrBy(CollaborationWorkItemRow::getRevision, 1)
            .set(CollaborationWorkItemRow::getUpdatedAt, clock.instant())) != 1) {
            throw ApiException.versionConflict(work.getRevision());
        }
        work.setStatus(status);
        work.setRevision(work.getRevision() + 1);
    }

    public void notify(CollaborationExecutionContext context, CollaborationActionRow action, String kind, String text) {
        var task = context.task();
        outbound.text(task.getEnterpriseId(), task.getConversationId(), context.participant().getBotBindingId(), task.getId(), action.getId(),
            "action:" + action.getId(), kind, text, null);
    }

    public static void requireOwner(CollaborationTaskRow task, CollaborationWorkItemRow work) {
        if (!work.getId().equals(task.getOwnerWorkId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "COLLABORATION_OWNER_REQUIRED", "只有负责最终交付的具体工作可以执行此操作。");
        }
    }

    public static String text(JsonNode input, String name, int maximum) {
        return ResourceInput.text(input.path(name).isTextual() ? input.path(name).asText() : null, name, maximum, true);
    }

    public static List<String> identifiers(JsonNode input, String name) {
        var values = input.path(name);
        if (!values.isArray() || values.size() > 20) {
            throw ApiException.invalidField(name, "请提供最多二十个有效编号。");
        }
        var result = new ArrayList<String>();
        for (var value : values) {
            result.add(ResourceInput.text(value.isTextual() ? value.asText() : null, name, 100, true));
        }
        return result.stream().distinct().toList();
    }
}
