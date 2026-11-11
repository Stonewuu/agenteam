package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomEntryFileMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomEntryMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.request.WebRoomMessageRequest;
import com.stonewu.agenteam.model.collaboration.entity.WebRoomFileView;
import com.stonewu.agenteam.model.file.response.FileSummaryView;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomEntryRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 房间内只记录能向发起人展示的消息，和派活、交付使用同一事务。 */
@Service
public class WebRoomTimeline {
    private final CollaborationRoomMapper rooms;
    private final CollaborationRoomEntryMapper entries;
    private final ChatPayloadCipher cipher;
    private final CollaborationRoomEntryFileMapper entryFiles;
    private final CollaborationParticipantMapper participants;
    private final ResourceVersionMapper versions;
    private final Clock clock;

    public WebRoomTimeline(CollaborationRoomMapper rooms, CollaborationRoomEntryMapper entries, ChatPayloadCipher cipher, Clock clock,
            CollaborationRoomEntryFileMapper entryFiles, CollaborationParticipantMapper participants, ResourceVersionMapper versions) {
        this.participants = participants;
        this.versions = versions;
        this.entryFiles = entryFiles;
        this.rooms = rooms;
        this.entries = entries;
        this.cipher = cipher;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CollaborationRoomEntryRow append(String enterprise, String roomId, String taskId, String participantId,
            String author, String key, String kind, String text) {
        return append(enterprise, roomId, taskId, participantId, author, key, kind, Map.of("text", text));
    }

    public CollaborationRoomEntryRow userMessage(AuthContext actor, String roomId, String taskId, String key, String text,
            WebRoomMessageRequest request, String recipientName) {
        return userMessage(actor, roomId, taskId, key, text, request, recipientName, Map.of());
    }

    public CollaborationRoomEntryRow userMessage(AuthContext actor, String roomId, String taskId, String key, String text,
            WebRoomMessageRequest request, String recipientName, Map<String, ?> queuedIntent) {
        Map<String, Object> body = new LinkedHashMap<>(queuedIntent);
        body.put("text", text);
        if (request != null) {
            body.put("intent", request.intent());
            body.put("replyToTaskId", request.taskId());
            body.put("recipientAgentId", request.recipientAgentId());
            body.put("recipientName", recipientName);
        }
        return append(actor.enterpriseId(), roomId, taskId, null, actor.user().displayName(), key, "user", body);
    }

    private CollaborationRoomEntryRow append(String enterprise, String roomId, String taskId, String participantId,
            String author, String key, String kind, Map<String, ?> body) {
        var room = rooms.lockRoom(enterprise, roomId);
        if (room == null) {
            throw new IllegalStateException(Messages.text("error.collaboration.webRoomTimeline.append.1"));
        }
        var existing = previous(room, key);
        if (existing != null) {
            return existing;
        }
        var row = ChatRecords.initialize(new CollaborationRoomEntryRow(), enterprise, clock.instant());
        row.setRoomId(roomId);
        row.setTaskId(taskId);
        row.setParticipantId(participantId);
        row.setAuthorName(author);
        row.setKind(kind);
        row.setDeliveryState("visible");
        row.setSourceKey(key);
        row.setSequenceNo(room.getNextSequence());
        row.setBodyEncrypted(cipher.encrypt(enterprise, "room-entry", row.getId(), body));
        advance(room);
        entries.insert(row);
        return row;
    }

    private void advance(CollaborationRoomRow room) {
        if (rooms.update(new LambdaUpdateWrapper<CollaborationRoomRow>().eq(CollaborationRoomRow::getEnterpriseId, room.getEnterpriseId())
            .eq(CollaborationRoomRow::getId, room.getId()).eq(CollaborationRoomRow::getNextSequence, room.getNextSequence())
            .setIncrBy(CollaborationRoomRow::getNextSequence, 1).set(CollaborationRoomRow::getUpdatedAt, clock.instant())) != 1) {
            throw ApiException.versionConflict(room.getNextSequence());
        }
    }

    public void admit(CollaborationRoomEntryRow row, String taskId) {
        var room = rooms.lockRoom(row.getEnterpriseId(), row.getRoomId());
        advance(room);
        if (entries.update(new LambdaUpdateWrapper<CollaborationRoomEntryRow>()
            .eq(CollaborationRoomEntryRow::getEnterpriseId, row.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, row.getRoomId())
            .eq(CollaborationRoomEntryRow::getId, row.getId()).in(CollaborationRoomEntryRow::getDeliveryState, "queued", "selected")
            .set(CollaborationRoomEntryRow::getDeliveryState, "starting").set(CollaborationRoomEntryRow::getTaskId, taskId)
            .set(CollaborationRoomEntryRow::getSequenceNo, room.getNextSequence()).set(CollaborationRoomEntryRow::getUpdatedAt, clock.instant())) != 1) {
            throw new IllegalStateException(Messages.text("error.collaboration.webRoomTimeline.admit.1"));
        }
    }

    public CollaborationRoomEntryRow previous(CollaborationRoomRow room, String key) {
        return entries.selectOne(new LambdaQueryWrapper<CollaborationRoomEntryRow>()
            .eq(CollaborationRoomEntryRow::getEnterpriseId, room.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, room.getId())
            .eq(CollaborationRoomEntryRow::getSourceKey, key));
    }

    public JsonNode content(CollaborationRoomEntryRow row) {
        return cipher.decrypt(row.getEnterpriseId(), "room-entry", row.getId(), row.getBodyEncrypted());
    }

    public CollaborationRoomEntryRow requirement(AuthContext actor, CollaborationTaskRow task, String operation, String text, WebRoomMessageRequest request, String recipientName) {
        var discussion = new MPJLambdaWrapper<CollaborationRoomRow>()
            .innerJoin(CollaborationTaskRow.class, on -> on.eq(CollaborationTaskRow::getEnterpriseId, CollaborationRoomRow::getEnterpriseId)
                .eq(CollaborationTaskRow::getWebRoomId, CollaborationRoomRow::getId).eq(CollaborationTaskRow::getId, CollaborationRoomRow::getCurrentTaskId))
            .eq(CollaborationRoomRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationRoomRow::getOwnerUserId, actor.userId())
            .eq(CollaborationRoomRow::getId, task.getWebRoomId()).eq(CollaborationTaskRow::getWebTopicId, task.getWebTopicId());
        if (rooms.selectJoinCount(discussion) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ROOM_TASK_CHANGED", Messages.text("error.collaboration.webRoomTimeline.requirement.1"));
        }
        String prefix = "web:" + task.getWebRoomId() + ":";
        String key = operation.startsWith(prefix) ? "user:" + operation.substring(prefix.length()) : operation;
        return userMessage(actor, task.getWebRoomId(), task.getId(), key, text, request, recipientName);
    }

    public void attach(CollaborationRoomEntryRow entry, String taskId) {
        if (entries.update(new LambdaUpdateWrapper<CollaborationRoomEntryRow>()
            .eq(CollaborationRoomEntryRow::getEnterpriseId, entry.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, entry.getRoomId())
            .eq(CollaborationRoomEntryRow::getId, entry.getId()).isNull(CollaborationRoomEntryRow::getTaskId)
            .set(CollaborationRoomEntryRow::getTaskId, taskId)) != 1) {
            throw new IllegalStateException(Messages.text("error.collaboration.webRoomTimeline.attach.1"));
        }
    }

    /** 同一讨论中其他岗位已公开的对话正文；单独开启新任务后使用新的讨论起点。 */
    public Page<CollaborationRoomEntryRow> otherMessages(CollaborationTaskRow task, int limit) {
        return entries.selectJoinPage(new Page<CollaborationRoomEntryRow>(1, limit, true), CollaborationRoomEntryRow.class,
            new MPJLambdaWrapper<CollaborationRoomEntryRow>().selectAll(CollaborationRoomEntryRow.class)
                .innerJoin(CollaborationTaskRow.class, on -> on.eq(CollaborationTaskRow::getEnterpriseId, CollaborationRoomEntryRow::getEnterpriseId)
                    .eq(CollaborationTaskRow::getWebRoomId, CollaborationRoomEntryRow::getRoomId).eq(CollaborationTaskRow::getId, CollaborationRoomEntryRow::getTaskId))
                .eq(CollaborationRoomEntryRow::getEnterpriseId, task.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, task.getWebRoomId())
                .eq(CollaborationTaskRow::getInitiatorUserId, task.getInitiatorUserId()).eq(CollaborationTaskRow::getWebTopicId, task.getWebTopicId())
                .ne(CollaborationRoomEntryRow::getTaskId, task.getId()).in(CollaborationRoomEntryRow::getKind, "user", "work_completed", "completed")
                .in(CollaborationRoomEntryRow::getDeliveryState, "visible", "starting")
                .orderByDesc(CollaborationRoomEntryRow::getSequenceNo));
    }

    public List<Entry> list(CollaborationRoomRow room, long before) {
        var query = new LambdaQueryWrapper<CollaborationRoomEntryRow>()
            .eq(CollaborationRoomEntryRow::getEnterpriseId, room.getEnterpriseId()).eq(CollaborationRoomEntryRow::getRoomId, room.getId())
            .in(CollaborationRoomEntryRow::getDeliveryState, "visible", "starting", "failed")
            .lt(before > 0, CollaborationRoomEntryRow::getSequenceNo, before).orderByDesc(CollaborationRoomEntryRow::getSequenceNo);
        var page = entries.selectPage(new Page<>(1, 100, false), query).getRecords().reversed();
        var ids = page.stream().filter(row -> "user".equals(row.getKind())).map(CollaborationRoomEntryRow::getId).toList();
        var attachments = entryFiles.forEntries(room.getEnterpriseId(), room.getOwnerUserId(), ids).stream().collect(
            Collectors.groupingBy(WebRoomFileView::getMessageId, Collectors.mapping(WebRoomFileView::file, Collectors.toList())));
        var appearances = authorAppearances(room, page);
        return page.stream().map(row -> {
            var appearance = appearances.get(row.getParticipantId());
            var body = content(row);
            return new Entry(row.getId(), row.getSequenceNo(), row.getTaskId(), row.getParticipantId(), row.getAuthorName(),
                "failed".equals(row.getDeliveryState()) ? "unprocessed" : row.getKind(),
                body.path("text").asText(), body.path("recipientName").asText(null), row.getCreatedAt().toString(), attachments.getOrDefault(row.getId(), List.of()),
                appearance == null ? null : appearance.path("icon").asText(null), appearance == null ? null : appearance.path("color").asText(null));
        }).toList();
    }

    public record Queued(String id, String agentId, String recipientName, String text, boolean selected) {
    }

    public List<Queued> queued(CollaborationRoomRow room, List<CollaborationRoomEntryRow> rows) {
        var files = entryFiles.forEntries(room.getEnterpriseId(), room.getOwnerUserId(), rows.stream().map(CollaborationRoomEntryRow::getId).toList())
            .stream().collect(Collectors.groupingBy(WebRoomFileView::getMessageId, Collectors.mapping(file -> file.file().name(), Collectors.joining("、"))));
        return rows.stream().map(row -> {
            var body = content(row);
            String text = body.path("text").asText();
            return new Queued(row.getId(), row.getRecipientAgentId(), body.path("recipientName").asText(),
                text.isBlank() ? files.getOrDefault(row.getId(), "") : text, "selected".equals(row.getDeliveryState()));
        }).toList();
    }

    /** 按消息作者固定的岗位版本读取头像，历史回复不会因员工改名或换头像而张冠李戴。 */
    private Map<String, JsonNode> authorAppearances(CollaborationRoomRow room, List<CollaborationRoomEntryRow> page) {
        var ids = page.stream().map(CollaborationRoomEntryRow::getParticipantId).filter(Objects::nonNull).distinct().toList();
        var result = new HashMap<String, JsonNode>();
        if (!ids.isEmpty()) {
            var roles = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
                .select(CollaborationParticipantRow::getId, CollaborationParticipantRow::getAgentVersionId)
                .eq(CollaborationParticipantRow::getEnterpriseId, room.getEnterpriseId()).eq(CollaborationParticipantRow::getWebRoomId, room.getId())
                .in(CollaborationParticipantRow::getId, ids));
            var snapshots = versions.findMany(room.getEnterpriseId(), roles.stream().map(CollaborationParticipantRow::getAgentVersionId).distinct().toList());
            for (var role : roles) {
                var version = snapshots.get(role.getAgentVersionId());
                if (version != null) {
                    result.put(role.getId(), version.config());
                }
            }
        }
        return result;
    }

    public record Entry(String id, long sequence, String taskId, String participantId, String authorName, String kind, String text, String recipientName, String createdAt, List<FileSummaryView> attachments, String authorIcon, String authorColor) {
    }
}
