package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.request.WebRoomMessageRequest;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatInputValidation;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 原发起人的补充形成新要求版本，正在执行的快照保持不变。 */
@Service
public class CollaborationInputService {
    private final EnterpriseMapper enterprises;
    private final CollaborationAccessService access;
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper works;
    private final CollaborationRunLinkMapper links;
    private final ChatMessageMapper messages;
    private final ChatOutboundMessageMapper outgoing;
    private final CollaborationActionService actions;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final CollaborationNotices outbound;
    private final WebRoomTimeline webTimeline;
    private final WebRoomAttachments webFiles;
    private final Clock clock;
    private final CollaborationUserOptionsService userOptions;
    private final CollaborationInputArtifacts inputArtifacts;

    public CollaborationInputService(EnterpriseMapper enterprises, CollaborationAccessService access, CollaborationTaskMapper tasks,
            CollaborationWorkItemMapper works, CollaborationRunLinkMapper links, ChatMessageMapper messages,
            ChatOutboundMessageMapper outgoing, CollaborationActionService actions, ChatPayloadCipher cipher,
            ResourceJson json, CollaborationNotices outbound, Clock clock, CollaborationUserOptionsService userOptions, CollaborationInputArtifacts inputArtifacts, WebRoomTimeline webTimeline, WebRoomAttachments webFiles) {
        this.webFiles = webFiles;
        this.webTimeline = webTimeline;
        this.enterprises = enterprises;
        this.access = access;
        this.tasks = tasks;
        this.works = works;
        this.links = links;
        this.messages = messages;
        this.outgoing = outgoing;
        this.actions = actions;
        this.cipher = cipher;
        this.json = json;
        this.outbound = outbound;
        this.clock = clock;
        this.userOptions = userOptions;
        this.inputArtifacts = inputArtifacts;
    }

    @Transactional
    public JsonNode append(AuthContext actor, String taskId, long expectedRevision, String operation,
            String text, String messageId) {
        return append(actor, taskId, expectedRevision, operation, text, messageId, json.tree(Map.of()));
    }

    @Transactional
    public JsonNode append(AuthContext actor, String taskId, long expectedRevision, String operation,
            String text, String messageId, JsonNode options) {
        return append(actor, taskId, expectedRevision, operation, text, messageId, options, List.of(), null, null, null);
    }

    @Transactional
    public JsonNode appendWeb(AuthContext actor, String taskId, long expectedRevision, String operation, String text, List<String> fileIds,
            WebRoomMessageRequest request, String recipientName) {
        return append(actor, taskId, expectedRevision, operation, text, null, json.tree(Map.of()), fileIds, request, recipientName, null);
    }

    @Transactional
    public void acceptQueued(AuthContext actor, CollaborationTaskRow target, CollaborationRoomEntryRow entry, String text, List<String> files) {
        append(actor, target.getId(), target.getRevision(), "room-turn:" + entry.getId(), text, null, json.tree(Map.of()),
            files, null, null, entry);
    }

    private JsonNode append(AuthContext actor, String taskId, long expectedRevision, String operation, String text,
            String messageId, JsonNode options, List<String> fileIds, WebRoomMessageRequest request, String recipientName,
            CollaborationRoomEntryRow queued) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var task = access.owned(actor, taskId);
        access.actor(task);
        ChatInputValidation.size(text, fileIds.size());
        if (text.isBlank() && fileIds.isEmpty()) {
            throw ApiException.invalidField("text", Messages.text("error.collaboration.collaborationInputRequest.text.1"));
        }
        var input = (ObjectNode) json.tree(Map.of("text", text, "messageId", messageId == null ? "" : messageId));
        input.set("options", options);
        if (request != null && request.recipientAgentId() != null) {
            input.put("recipientAgentId", request.recipientAgentId());
        }
        if (!fileIds.isEmpty()) {
            input.set("attachmentIds", json.tree(fileIds));
        }
        var replay = actions.replay(actor.enterpriseId(), taskId, operation, input);
        if (replay.isPresent()) {
            return replay.get();
        }
        mutable(task, expectedRevision, true);
        var webEntry = queued != null ? queued : task.fromWebRoom() ? webTimeline.requirement(actor, task, operation, text, request, recipientName) : null;
        boolean memberTurn = queued != null || request != null;
        if (messageId != null) {
            var message = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId())
                .eq(ChatMessageRow::getId, messageId).eq(ChatMessageRow::getConversationId, task.getConversationId())
                .eq(ChatMessageRow::getSenderUserId, actor.userId()));
            if (message == null || message.getTaskId() != null && !message.getTaskId().equals(taskId)) {
                throw ApiException.invalidField("messageId", Messages.text("error.collaboration.collaborationInputService.append.1"));
            }
        }
        var objective = (ObjectNode) cipher.decrypt(actor.enterpriseId(), "task-objective", taskId, task.getObjectiveEncrypted());
        var additional = webEntry == null ? input : input.deepCopy().put("roomEntryId", webEntry.getId());
        objective.withArray("additionalRequirements").add(additional);
        var action = actions.begin(task, null, null, null, operation, "input_added", input);
        // 点名补充不会替换已委派的工作。仍有其他岗位处理原要求时，保留它们校验工具所用的版本。
        boolean otherWork = memberTurn && works.exists(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationWorkItemRow::getTaskId, taskId)
            .ne(CollaborationWorkItemRow::getId, task.getOwnerWorkId())
            .and(q -> q.isNotNull(CollaborationWorkItemRow::getActiveRunId).or()
                .in(CollaborationWorkItemRow::getStatus, "ready", "running", "waiting_input", "waiting_dependency", "waiting_approval", "blocked")));
        long revision = otherWork ? task.getInputRevision() : task.getInputRevision() + 1;
        boolean uncertain = uncertain(task);
        boolean followUp = "completed".equals(task.getStatus());
        var deadline = followUp ? clock.instant().plusSeconds(60L * Math.max(10,
            Math.min(10080, json.read(task.getPolicySnapshot()).path("deadlineMinutes").asInt(1440)))) : task.getDeadlineAt();
        changed(tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getRevision, expectedRevision)
            .set(CollaborationTaskRow::getInputRevision, revision).set(CollaborationTaskRow::getStatus, uncertain ? "blocked" : "active")
            .set(CollaborationTaskRow::getErrorCode, uncertain ? "TOOL_RESULT_UNKNOWN" : null)
            .set(followUp, CollaborationTaskRow::getFinishedAt, null).set(followUp, CollaborationTaskRow::getFinalArtifactId, null)
            .set(followUp, CollaborationTaskRow::getDeadlineAt, deadline)
            .set(followUp || queued != null, CollaborationTaskRow::getPolicySnapshot,
                followUp || queued != null ? continuedPolicy(task) : task.getPolicySnapshot())
            .set(CollaborationTaskRow::getObjectiveEncrypted, cipher.encrypt(actor.enterpriseId(), "task-objective", taskId, objective))
            .setIncrBy(CollaborationTaskRow::getRevision, 1).set(CollaborationTaskRow::getUpdatedAt, clock.instant())));
        if (!memberTurn) {
            links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationRunLinkRow::getTaskId, taskId).isNull(CollaborationRunLinkRow::getTerminalObservedAt)
                .set(CollaborationRunLinkRow::getRequestedStopReason, "input_changed"));
        }
        works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, taskId).isNull(CollaborationWorkItemRow::getActiveRunId)
            .and(query -> query.isNull(CollaborationWorkItemRow::getErrorCode).or().ne(CollaborationWorkItemRow::getErrorCode, "TOOL_RESULT_UNKNOWN"))
            .eq(CollaborationWorkItemRow::getId, task.getOwnerWorkId())
            .set(CollaborationWorkItemRow::getStatus, "ready").set(CollaborationWorkItemRow::getResumeReason, "input_changed")
            .set(CollaborationWorkItemRow::getInputRevision, revision).set(CollaborationWorkItemRow::getContextId, null)
            .set(followUp, CollaborationWorkItemRow::getDeadlineAt, deadline)
            .set(CollaborationWorkItemRow::getLeaseOwner, null).set(CollaborationWorkItemRow::getLeaseUntil, null)
            .setIncrBy(CollaborationWorkItemRow::getRevision, 1).set(CollaborationWorkItemRow::getUpdatedAt, clock.instant()));
        if (!memberTurn) {
            works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationWorkItemRow::getTaskId, taskId).ne(CollaborationWorkItemRow::getId, task.getOwnerWorkId())
                .isNull(CollaborationWorkItemRow::getActiveRunId).in(CollaborationWorkItemRow::getStatus, "ready", "blocked", "waiting_input", "waiting_dependency")
                .and(query -> query.isNull(CollaborationWorkItemRow::getErrorCode).or().ne(CollaborationWorkItemRow::getErrorCode, "TOOL_RESULT_UNKNOWN"))
                .set(CollaborationWorkItemRow::getStatus, "blocked").set(CollaborationWorkItemRow::getErrorCode, "TASK_INPUT_CHANGED")
                .set(CollaborationWorkItemRow::getLeaseOwner, null).set(CollaborationWorkItemRow::getLeaseUntil, null)
                .setIncrBy(CollaborationWorkItemRow::getRevision, 1).set(CollaborationWorkItemRow::getUpdatedAt, clock.instant()));
            outgoing.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, actor.enterpriseId())
                .eq(ChatOutboundMessageRow::getTaskId, taskId).in(ChatOutboundMessageRow::getStatus, "pending", "retry_wait", "leased")
                .set(ChatOutboundMessageRow::getStatus, "cancelled").set(ChatOutboundMessageRow::getErrorCode, "TASK_INPUT_CHANGED")
                .set(ChatOutboundMessageRow::getLeaseOwner, null).set(ChatOutboundMessageRow::getLeaseUntil, null));
        }
        userOptions.apply(task, options, additional, objective);
        if (messageId != null) {
            var current = access.require(actor.enterpriseId(), taskId);
            var owner = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationWorkItemRow::getTaskId, taskId).eq(CollaborationWorkItemRow::getId, current.getOwnerWorkId()));
            inputArtifacts.register(actor, current, owner, messageId);
        }
        if (queued != null && !fileIds.isEmpty()) {
            webFiles.publish(actor, access.require(actor.enterpriseId(), taskId), queued);
        } else if (webEntry != null && !fileIds.isEmpty()) {
            webFiles.bind(actor, access.require(actor.enterpriseId(), taskId), webEntry, fileIds);
        }
        // 引用消息来自本任务的原发起人，和补充要求一并固定保存。
        changed(tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getId, taskId).set(CollaborationTaskRow::getObjectiveEncrypted,
                cipher.encrypt(actor.enterpriseId(), "task-objective", taskId, objective))));
        if (uncertain) {
            notify(task, action.getId(), "input_added", Messages.text("text.collaboration.collaborationInputService.append.1"));
        } else if (!task.fromWebRoom()) {
            outbound.send(task, null, action.getId(),
                "action:" + action.getId(), "input_added", Messages.text("text.collaboration.collaborationInputService.append.2", task.getShortCode()), true, null);
        }
        String status = uncertain ? "blocked" : "active";
        return actions.finish(action, webEntry == null ? Map.of("taskId", taskId, "inputRevision", revision, "status", status)
            : Map.of("taskId", taskId, "inputRevision", revision, "status", status, "entryId", webEntry.getId()));
    }

    @Transactional
    public JsonNode cancel(AuthContext actor, String taskId, long expectedRevision, String operation, String reason) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var task = access.owned(actor, taskId);
        var input = json.tree(Map.of("reason", reason == null ? "" : reason));
        var replay = actions.replay(actor.enterpriseId(), taskId, operation, input);
        if (replay.isPresent()) {
            return replay.get();
        }
        mutable(task, expectedRevision, false);
        var action = actions.begin(task, null, null, null, operation, "cancel_requested", input);
        long active = links.selectCount(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRunLinkRow::getTaskId, taskId).isNull(CollaborationRunLinkRow::getTerminalObservedAt));
        String status = active == 0 ? "cancelled" : "cancelling";
        boolean uncertain = uncertain(task);
        changed(tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getId, taskId).eq(CollaborationTaskRow::getRevision, expectedRevision)
            .set(CollaborationTaskRow::getStatus, status).set(active == 0, CollaborationTaskRow::getFinishedAt, clock.instant())
            .set(uncertain, CollaborationTaskRow::getErrorCode, "TOOL_RESULT_UNKNOWN")
            .setIncrBy(CollaborationTaskRow::getRevision, 1).set(CollaborationTaskRow::getUpdatedAt, clock.instant())));
        works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, taskId).isNull(CollaborationWorkItemRow::getActiveRunId)
            .in(CollaborationWorkItemRow::getStatus, "ready", "blocked", "waiting_dependency", "waiting_input", "waiting_approval")
            .set(CollaborationWorkItemRow::getStatus, "cancelled").set(CollaborationWorkItemRow::getLeaseOwner, null)
            .set(CollaborationWorkItemRow::getLeaseUntil, null).setIncrBy(CollaborationWorkItemRow::getRevision, 1));
        links.update(new LambdaUpdateWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRunLinkRow::getTaskId, taskId).isNull(CollaborationRunLinkRow::getTerminalObservedAt)
            .set(CollaborationRunLinkRow::getRequestedStopReason, "user_cancel"));
        outgoing.update(new LambdaUpdateWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatOutboundMessageRow::getTaskId, taskId).in(ChatOutboundMessageRow::getStatus, "pending", "retry_wait", "leased")
            .set(ChatOutboundMessageRow::getStatus, "cancelled").set(ChatOutboundMessageRow::getLeaseOwner, null).set(ChatOutboundMessageRow::getLeaseUntil, null));
        if (active == 0) {
            notify(task, action.getId(), "cancelled", Messages.text("error.collaboration.collaborationInputService.cancel.1", task.getShortCode(), (uncertain ? Messages.text("error.collaboration.collaborationInputService.cancel.2") : "")));
        }
        return actions.finish(action, Map.of("taskId", taskId, "status", status));
    }

    private void notify(CollaborationTaskRow task, String actionId, String kind, String text) {
        outbound.send(task, null, actionId,
            "action:" + actionId, kind, text, false, null);
    }

    private boolean uncertain(CollaborationTaskRow task) {
        return works.exists(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, task.getId()).eq(CollaborationWorkItemRow::getErrorCode, "TOOL_RESULT_UNKNOWN"));
    }

    private String continuedPolicy(CollaborationTaskRow task) {
        var policy = (ObjectNode) json.read(task.getPolicySnapshot());
        renewBudget(policy, "maxRuns", "runsPerRequest", task.getRunCount(), 40);
        renewBudget(policy, "maxWorks", "worksPerRequest", task.getWorkCount(), 20);
        renewBudget(policy, "maxHandoffs", "handoffsPerRequest", task.getHandoffCount(), 8);
        return json.write(policy);
    }

    /** 历史计数保留，每次用户追问重新获得原配置的执行额度，避免把会话历史当作本次额度。 */
    private void renewBudget(ObjectNode policy, String limitField, String budgetField, int completed, int defaultLimit) {
        int budget = Math.max("maxHandoffs".equals(limitField) ? 0 : 1,
            policy.path(budgetField).asInt(policy.path(limitField).asInt(defaultLimit)));
        policy.put(budgetField, budget).put(limitField, Math.addExact(completed, budget));
    }

    private void mutable(CollaborationTaskRow task, long expectedRevision, boolean allowCompleted) {
        if (task.getRevision() != expectedRevision) {
            throw ApiException.versionConflict(task.getRevision());
        }
        if ((!allowCompleted && "completed".equals(task.getStatus())) || Set.of("cancelled", "failed", "cancelling").contains(task.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_TASK_CLOSED", Messages.text("error.collaboration.collaborationInputService.mutable.1"));
        }
    }

    private void changed(int count) {
        if (count != 1) {
            throw new IllegalStateException(Messages.text("error.collaboration.collaborationInputService.changed.1"));
        }
    }
}
