package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.RunQuestionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.request.WebRoomCreateRequest;
import com.stonewu.agenteam.model.collaboration.request.WebRoomMessageRequest;
import com.stonewu.agenteam.model.execution.entity.RunQuestionRow;
import com.stonewu.agenteam.model.execution.response.RunQuestionView;
import com.stonewu.agenteam.service.chat.ChatInputValidation;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.execution.RunQuestionContent;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 发起人在网页对话中点名成员，各成员分别接收补充消息和执行工作。 */
@Service
public class WebRoomService {
    private final CollaborationRoomMapper rooms;
    private final EnterpriseMapper enterprises;
    private final WebRoomRoster roster;
    private final WebRoomTimeline timeline;
    private final WebRoomAttachments attachments;
    private final CollaborationTaskCreationService creation;
    private final CollaborationAccessService access;
    private final CollaborationInputService inputs;
    private final CollaborationTaskMapper tasks;
    private final RunQuestionMapper questions;
    private final RunQuestionContent questionContent;
    private final Clock clock;
    private final WebRoomQueue queue;

    public WebRoomService(CollaborationRoomMapper rooms, EnterpriseMapper enterprises, WebRoomRoster roster, WebRoomTimeline timeline,
            CollaborationTaskCreationService creation, CollaborationAccessService access, CollaborationInputService inputs,
            CollaborationTaskMapper tasks, RunQuestionMapper questions, RunQuestionContent questionContent, WebRoomAttachments attachments, Clock clock, WebRoomQueue queue) {
        this.queue = queue;
        this.attachments = attachments;
        this.rooms = rooms;
        this.enterprises = enterprises;
        this.roster = roster;
        this.timeline = timeline;
        this.creation = creation;
        this.access = access;
        this.inputs = inputs;
        this.tasks = tasks;
        this.questions = questions;
        this.questionContent = questionContent;
        this.clock = clock;
    }

    @Transactional
    public Map<String, String> create(AuthContext actor, WebRoomCreateRequest request) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        String title = request.title() == null ? "" : request.title().strip();
        if (title.codePointCount(0, title.length()) > 160) {
            throw ApiException.invalidField("title", Messages.text("error.collaboration.webRoomService.create.1"));
        }
        var room = ChatRecords.initialize(new CollaborationRoomRow(), actor.enterpriseId(), clock.instant());
        room.setOwnerUserId(actor.userId());
        room.setTitle(title.isBlank() ? Messages.text("text.collaboration.webRoomService.create.1") : title);
        room.setNextSequence(1L);
        var selected = roster.prepareMembers(actor, room, request.agentIds());
        if (title.isBlank()) {
            String names = String.join("、", selected.stream().map(member -> member.getDisplayName()).toList());
            room.setTitle(names.substring(0, names.offsetByCodePoints(0, Math.min(160, names.codePointCount(0, names.length())))));
        }
        rooms.insert(room);
        roster.saveMembers(selected);
        return Map.of("roomId", room.getId());
    }

    public CollaborationRoomRow owned(AuthContext actor, String id) {
        var room = rooms.selectOne(new LambdaQueryWrapper<CollaborationRoomRow>()
            .eq(CollaborationRoomRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationRoomRow::getOwnerUserId, actor.userId())
            .eq(CollaborationRoomRow::getId, id));
        if (room == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_UNAVAILABLE", Messages.text("error.collaboration.webRoomService.owned.1"));
        }
        return room;
    }

    public List<Summary> list(AuthContext actor, int page) {
        if (page < 1 || page > 10000) {
            throw ApiException.invalidField("page", Messages.text("error.collaboration.webRoomService.list.1"));
        }
        return rooms.selectPage(new Page<>(page, 30, false), new LambdaQueryWrapper<CollaborationRoomRow>()
            .eq(CollaborationRoomRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationRoomRow::getOwnerUserId, actor.userId())
            .orderByDesc(CollaborationRoomRow::getUpdatedAt, CollaborationRoomRow::getId)).getRecords().stream().map(this::summary).toList();
    }

    private Summary summary(CollaborationRoomRow room) {
        return new Summary(room.getId(), room.getTitle(), room.getUpdatedAt().toString());
    }

    @Transactional(readOnly = true)
    public Detail detail(AuthContext actor, String roomId, long before) {
        var room = owned(actor, roomId);
        var task = room.getCurrentTaskId() == null ? null : access.owned(actor, room.getCurrentTaskId());
        var members = roster.describe(room, roster.speakers(task));
        var active = tasks.selectList(new LambdaQueryWrapper<CollaborationTaskRow>().select(CollaborationTaskRow::getId)
            .eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationTaskRow::getWebRoomId, roomId)
            .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId()).notIn(CollaborationTaskRow::getStatus, "completed", "cancelled", "failed"))
            .stream().map(CollaborationTaskRow::getId).toList();
        var pending = active.isEmpty() ? List.<RunQuestionView>of() : questions.selectList(new LambdaQueryWrapper<RunQuestionRow>()
            .eq(RunQuestionRow::getEnterpriseId, actor.enterpriseId()).in(RunQuestionRow::getTaskId, active)
            .eq(RunQuestionRow::getUserId, actor.userId()).eq(RunQuestionRow::getStatus, "pending")
            .orderByAsc(RunQuestionRow::getCreatedAt, RunQuestionRow::getId)).stream().map(questionContent::view).toList();
        var owner = task == null ? null : access.participant(task, task.getOwnerParticipantId());
        var current = task == null ? null : new Task(task.getId(), task.getStatus(), task.getInputRevision(), task.getRevision(),
            task.getErrorCode(), owner.getAgentId(), roster.acceptsFiles(actor.enterpriseId(), owner.getAgentId(), owner.getAgentVersionId()));
        return new Detail(summary(room), members, current, timeline.list(room, before), pending, active, timeline.queued(room, queue.list(room)));
    }

    @Transactional
    public Map<String, String> send(AuthContext actor, String roomId, WebRoomMessageRequest request) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var room = owned(actor, roomId);
        String text = request.text() == null ? "" : request.text().strip();
        var fileIds = WebRoomAttachments.ids(request.attachmentIds());
        if (text.isEmpty() && fileIds.isEmpty()) {
            throw ApiException.invalidField("text", Messages.text("error.collaboration.webRoomService.send.1"));
        }
        ChatInputValidation.size(text, fileIds.size());
        if (request.clientMessageId() == null || !request.clientMessageId().matches("[a-zA-Z0-9_-]{1,64}")) {
            throw ApiException.invalidField("clientMessageId", Messages.text("error.collaboration.webRoomService.send.2"));
        }
        String key = "user:" + request.clientMessageId();
        var previous = timeline.previous(room, key);
        if (previous != null) {
            var body = timeline.content(previous);
            boolean initial = previous.getId().equals(access.owned(actor, previous.getTaskId()).getWebOriginEntryId());
            boolean sameFiles = body.has("queuedFileIds") ? queuedFiles(body).equals(fileIds) : attachments.matches(previous, fileIds);
            String intent = body.path("intent").asText(initial ? "start" : "continue");
            String reference = body.has("replyToTaskId") ? body.path("replyToTaskId").asText(null) : initial ? null : previous.getTaskId();
            if (!body.path("text").asText().equals(text) || !sameFiles || !intent.equals(request.intent())
                || !Objects.equals(reference, request.taskId()) || !Objects.equals(body.path("recipientAgentId").asText(null), request.recipientAgentId())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROOM_MESSAGE_CONFLICT", Messages.text("error.collaboration.webRoomService.send.3"));
            }
            if (Set.of("queued", "selected").contains(previous.getDeliveryState())) {
                return Map.of("taskId", previous.getTaskId(), "entryId", previous.getId(), "status", "queued");
            }
            return Map.of("taskId", previous.getTaskId(), "entryId", previous.getId());
        }
        String recipient = request.recipientAgentId();
        String recipientName = null;
        if (recipient != null) {
            recipientName = roster.members(room).stream().filter(member -> member.getAgentId().equals(recipient))
                .map(member -> member.getDisplayName()).findFirst()
                .orElseThrow(() -> ApiException.invalidField("recipientAgentId", Messages.text("error.collaboration.webRoomService.send.4")));
        }
        var current = room.getCurrentTaskId() == null ? null : access.owned(actor, room.getCurrentTaskId());
        if ("continue".equals(request.intent())) {
            if (current == null || request.taskId() == null || request.expectedInputRevision() == null || request.expectedInputRevision() < 1) {
                throw changed();
            }
            var reference = current.getId().equals(request.taskId()) ? current : access.owned(actor, request.taskId());
            if (!roomId.equals(reference.getWebRoomId()) || !Objects.equals(reference.getWebTopicId(), current.getWebTopicId())
                || recipient == null && !reference.getId().equals(current.getId())) {
                throw changed();
            }
            var owner = access.participant(reference, reference.getOwnerParticipantId());
            var target = reference;
            if (recipient != null && !owner.getAgentId().equals(recipient)) {
                var speaker = roster.speakers(current).get(recipient);
                if (speaker == null) {
                    return start(actor, room, request, text, fileIds, key, recipientName, current.getWebTopicId());
                }
                target = access.owned(actor, speaker.getTaskId());
            }
            if (recipient == null && target.getId().equals(reference.getId()) && !Objects.equals(target.getInputRevision(), request.expectedInputRevision())
                && !queue.mustWait(room, owner.getAgentId(), target, false)) {
                throw changed();
            }
            return reply(actor, room, target, request, text, fileIds, recipientName);
        }
        boolean firstMention = "mention".equals(request.intent());
        if (!firstMention && !"start".equals(request.intent()) || request.taskId() != null || request.expectedInputRevision() != null) {
            throw ApiException.invalidField("intent", Messages.text("error.collaboration.webRoomService.send.5"));
        }
        if (recipient == null) {
            throw ApiException.invalidField("recipientAgentId", Messages.text("error.collaboration.webRoomService.send.6"));
        }
        if (firstMention && current != null) {
            // 两个页面都从空对话发言时，共用先到消息打开的讨论，后到的点名不会打断它。
            var speaker = roster.speakers(current).get(recipient);
            if (speaker == null) {
                return start(actor, room, request, text, fileIds, key, recipientName, current.getWebTopicId());
            }
            var target = access.owned(actor, speaker.getTaskId());
            return reply(actor, room, target, request, text, fileIds, recipientName);
        }
        if (current != null && !Set.of("completed", "cancelled", "failed").contains(current.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TASK_ACTIVE", Messages.text("error.collaboration.webRoomService.send.7"));
        }
        return start(actor, room, request, text, fileIds, key, recipientName, null);
    }

    private Map<String, String> reply(AuthContext actor, CollaborationRoomRow room, CollaborationTaskRow target, WebRoomMessageRequest request,
            String text, List<String> fileIds, String recipientName) {
        var owner = access.participant(target, target.getOwnerParticipantId());
        access.requireRole(target, owner);
        focus(room, target.getId());
        if (queue.mustWait(room, owner.getAgentId(), target, request.recipientAgentId() != null)) {
            return enqueue(actor, room, target, request, text, fileIds, owner.getAgentId(), owner.getAgentVersionId(),
                recipientName == null ? owner.getRoleName() : recipientName, false, target.getWebTopicId());
        }
        var result = inputs.appendWeb(actor, target.getId(), target.getRevision(), "web:" + room.getId() + ":" + request.clientMessageId(),
            text, fileIds, request, recipientName);
        return Map.of("taskId", target.getId(), "entryId", result.required("entryId").asText());
    }

    private Map<String, String> start(AuthContext actor, CollaborationRoomRow room, WebRoomMessageRequest request,
            String text, List<String> fileIds, String key, String recipientName, String topic) {
        String roomId = room.getId();
        requireStartCapacity(actor, roomId, topic == null);
        var fixed = roster.freeze(actor, room, request.recipientAgentId());
        var recipient = fixed.stream().filter(value -> value.getAgentId().equals(request.recipientAgentId())).findFirst().orElseThrow();
        if (room.getCurrentTaskId() != null && queue.mustWait(room, recipient.getAgentId(), null, true)) {
            return enqueue(actor, room, access.owned(actor, room.getCurrentTaskId()), request, text, fileIds,
                recipient.getAgentId(), recipient.getAgentVersionId(), recipientName, true, topic);
        }
        var entry = timeline.userMessage(actor, roomId, null, key, text, request, recipientName);
        var task = creation.createWeb(actor, roomId, entry.getId(), room.getRevision(), fixed, recipient, text, topic == null ? entry.getId() : topic);
        timeline.attach(entry, task.getId());
        attachments.bind(actor, task, entry, fileIds);
        focus(room, task.getId());
        return Map.of("taskId", task.getId(), "entryId", entry.getId());
    }

    private void requireStartCapacity(AuthContext actor, String roomId, boolean newTopic) {
        var recent = tasks.selectList(new LambdaQueryWrapper<CollaborationTaskRow>().select(CollaborationTaskRow::getWebTopicId, CollaborationTaskRow::getWebOriginEntryId)
            .eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationTaskRow::getWebRoomId, roomId)
            .gt(CollaborationTaskRow::getCreatedAt, clock.instant().minusSeconds(60)));
        if (recent.size() >= 40 || newTopic && recent.stream().filter(task -> task.getWebTopicId().equals(task.getWebOriginEntryId())).count() >= 5) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ROOM_TASK_RATE_LIMIT", Messages.text("error.collaboration.webRoomService.requireStartCapacity.1"));
        }
    }

    private Map<String, String> enqueue(AuthContext actor, CollaborationRoomRow room, CollaborationTaskRow anchor,
            WebRoomMessageRequest request, String text, List<String> files, String agent, String version, String name,
            boolean newSpeaker, String topic) {
        var entry = timeline.userMessage(actor, room.getId(), anchor.getId(), "user:" + request.clientMessageId(), text,
            request, name, Map.of("newSpeaker", newSpeaker, "queuedTopic", topic == null ? "" : topic, "queuedVersion", version, "queuedFileIds", files));
        attachments.capture(actor, entry, agent, version, files);
        queue.stage(entry, agent);
        return Map.of("taskId", anchor.getId(), "entryId", entry.getId(), "status", "queued");
    }

    @Transactional
    public void changeQueue(AuthContext actor, String roomId, String entryId, boolean prioritize) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var room = owned(actor, roomId);
        var entry = queue.find(room, entryId);
        queue.change(room, entry, prioritize);
        if (!prioritize) {
            attachments.withdraw(entry);
        }
    }

    @Transactional
    public void deliver(CollaborationRoomEntryRow candidate) {
        String enterprise = candidate.getEnterpriseId();
        enterprises.lockEnterprise(enterprise).orElseThrow();
        var room = rooms.lockRoom(enterprise, candidate.getRoomId());
        var entry = queue.find(room, candidate.getId());
        if (entry == null || !Set.of("queued", "selected").contains(entry.getDeliveryState())) {
            return;
        }
        var anchor = access.require(enterprise, entry.getTaskId());
        var actor = access.actor(anchor);
        if (!actor.userId().equals(room.getOwnerUserId()) || !room.getId().equals(anchor.getWebRoomId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_UNAVAILABLE", Messages.text("error.collaboration.webRoomService.owned.1"));
        }
        var body = timeline.content(entry);
        String agent = entry.getRecipientAgentId();
        String topic = body.path("queuedTopic").asText();
        var target = anchor;
        if (body.path("newSpeaker").asBoolean() || !access.participant(anchor, anchor.getOwnerParticipantId()).getAgentId().equals(agent)) {
            var speaker = topic.isEmpty() ? null : roster.speakers(anchor).get(agent);
            target = speaker == null ? null : access.require(enterprise, speaker.getTaskId());
        }
        if (!queue.canAdmit(entry, target == null ? anchor : target, target != null)) {
            return;
        }
        String text = body.path("text").asText();
        if (target == null) {
            requireStartCapacity(actor, room.getId(), topic.isEmpty());
            var roles = roster.freeze(actor, room, agent);
            var recipient = roles.stream().filter(role -> agent.equals(role.getAgentId())).findFirst().orElseThrow();
            if (!body.path("queuedVersion").asText(recipient.getAgentVersionId()).equals(recipient.getAgentVersionId())) {
                throw new ApiException(HttpStatus.CONFLICT, "ROOM_MEMBER_CHANGED", Messages.text("error.collaboration.webRoomService.deliver.1"));
            }
            target = creation.createWeb(actor, room.getId(), entry.getId(), room.getRevision(), roles, recipient, text,
                topic.isEmpty() ? entry.getId() : topic);
            attachments.publish(actor, target, entry);
        } else {
            access.requireRole(target, access.participant(target, target.getOwnerParticipantId()));
            if (Set.of("failed", "cancelling", "cancelled").contains(target.getStatus())
                || "TOOL_RESULT_UNKNOWN".equals(target.getErrorCode()) || "blocked".equals(target.getStatus()) && target.getErrorCode() != null) {
                queue.updateState(entry, "failed");
                return;
            }
            inputs.acceptQueued(actor, target, entry, text, queuedFiles(body));
        }
        timeline.admit(entry, target.getId());
        if (anchor.getId().equals(room.getCurrentTaskId())) {
            focus(room, target.getId());
        }
    }

    @Transactional
    public void failQueued(CollaborationRoomEntryRow candidate) {
        enterprises.lockEnterprise(candidate.getEnterpriseId()).orElseThrow();
        var room = rooms.lockRoom(candidate.getEnterpriseId(), candidate.getRoomId());
        var row = queue.find(room, candidate.getId());
        if (row != null && Set.of("queued", "selected").contains(row.getDeliveryState()) && candidate.getRevision().equals(row.getRevision())) {
            queue.updateState(row, "failed");
        }
    }

    private static List<String> queuedFiles(JsonNode body) {
        var files = new ArrayList<String>();
        body.path("queuedFileIds").forEach(id -> files.add(id.asText()));
        return files;
    }

    private void focus(CollaborationRoomRow room, String taskId) {
        if (!taskId.equals(room.getCurrentTaskId()) && rooms.update(new LambdaUpdateWrapper<CollaborationRoomRow>()
            .eq(CollaborationRoomRow::getEnterpriseId, room.getEnterpriseId()).eq(CollaborationRoomRow::getOwnerUserId, room.getOwnerUserId())
            .eq(CollaborationRoomRow::getId, room.getId()).set(CollaborationRoomRow::getCurrentTaskId, taskId)) != 1) {
            throw new IllegalStateException(Messages.text("error.collaboration.webRoomService.focus.1"));
        }
    }

    private static ApiException changed() {
        return new ApiException(HttpStatus.CONFLICT, "TASK_INPUT_CHANGED", Messages.text("error.collaboration.webRoomService.changed.1"));
    }

    public record Summary(String id, String title, String updatedAt) {
    }

    public record Task(String id, String status, long inputRevision, long revision, String errorCode, String ownerAgentId, boolean attachmentsEnabled) {
    }

    public record Detail(Summary room, List<WebRoomRoster.Member> members, Task task, List<WebRoomTimeline.Entry> entries, List<RunQuestionView> questions, List<String> activeTaskIds, List<WebRoomTimeline.Queued> queuedMessages) {
    }
}
