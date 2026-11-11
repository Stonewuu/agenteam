package com.stonewu.agenteam.service.collaboration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRoomMemberMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskViewMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRoomMemberRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskSummary;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 搜索本人全部任务，分页完成后批量补齐来源和岗位，不逐条查询详情。 */
@Service
public class CollaborationTaskListService {
    private static final Set<String> STATUSES = Set.of("active", "waiting_input", "waiting_approval", "blocked", "completed", "failed", "cancelled", "cancelling");
    private final CollaborationTaskMapper tasks;
    private final CollaborationParticipantMapper participants;
    private final ChatConversationMapper conversations;
    private final ChatIdentityService identities;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final CollaborationMessageDisplayService display;
    private final ListPagination pagination;
    private final CollaborationRoomMapper rooms;
    private final CollaborationRoomMemberMapper roomMembers;

    public CollaborationTaskListService(CollaborationTaskMapper tasks, CollaborationParticipantMapper participants,
            ChatConversationMapper conversations, ChatIdentityService identities, ChatPayloadCipher cipher,
            ResourceJson json, CollaborationMessageDisplayService display, ListPagination pagination,
            CollaborationRoomMapper rooms, CollaborationRoomMemberMapper roomMembers) {
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.tasks = tasks;
        this.participants = participants;
        this.conversations = conversations;
        this.identities = identities;
        this.cipher = cipher;
        this.json = json;
        this.display = display;
        this.pagination = pagination;
    }

    public PageResponse<CollaborationTaskSummary> list(AuthContext actor, String conversation, String status,
            String search, String cursor, Integer requested) {
        identities.actor(actor.enterpriseId(), actor.userId());
        String keyword = pagination.query(search).toLowerCase(Locale.ROOT);
        String selectedStatus = status == null || status.isBlank() ? null : status;
        if (selectedStatus != null && !STATUSES.contains(selectedStatus)) {
            throw ApiException.invalidField("status", Messages.text("error.collaboration.collaborationTaskListService.list.1"));
        }
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "collaboration/tasks",
            json.write(json.tree(List.of(conversation == null ? "" : conversation, selectedStatus == null ? "" : selectedStatus, keyword))), "updated_desc");
        var after = pagination.read(cursor, binding);
        var matches = findTasks(actor, conversation, selectedStatus, keyword, after, limit, false);
        var page = pagination.page(matches, limit, binding, match -> new PagePosition(match.task().getUpdatedAt(), match.task().getId()));
        return new PageResponse<>(summaries(actor, page.items()), page.nextCursor(), page.hasMore());
    }

    /** 工作空间把尚未发起任务的对话和已有任务按更新时间一起排列。 */
    @Transactional(readOnly = true)
    public PageResponse<DirectoryEntry> directory(AuthContext actor, String status, String search, String cursor, Integer requested) {
        identities.actor(actor.enterpriseId(), actor.userId());
        String keyword = pagination.query(search).toLowerCase(Locale.ROOT);
        String selected = status == null || status.isBlank() ? null : status;
        if (selected != null && !"not_started".equals(selected) && !STATUSES.contains(selected)) {
            throw ApiException.invalidField("status", Messages.text("error.collaboration.collaborationTaskListService.list.1"));
        }
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "collaboration/directory",
            json.write(json.tree(List.of(selected == null ? "" : selected, keyword))), "updated_desc");
        var after = pagination.read(cursor, binding);
        var entries = new ArrayList<DirectoryEntry>();
        if (!"not_started".equals(selected)) {
            for (var task : summaries(actor, findTasks(actor, null, selected, keyword, after, limit, true))) {
                entries.add(new DirectoryEntry(task, null));
            }
        }
        if (selected == null || "not_started".equals(selected)) {
            emptyRooms(actor, keyword, after, limit).forEach(room -> entries.add(new DirectoryEntry(null, room)));
        }
        entries.sort(Comparator.comparing((DirectoryEntry e) -> e.position().time()).thenComparing(e -> e.position().id())
            .thenComparing(e -> e.task() != null).reversed());
        return pagination.page(entries, limit, binding, DirectoryEntry::position);
    }

    private LambdaQueryWrapper<CollaborationRoomRow> roomSearch(AuthContext actor, String keyword) {
        String literal = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return new LambdaQueryWrapper<CollaborationRoomRow>().eq(CollaborationRoomRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRoomRow::getOwnerUserId, actor.userId()).like(!literal.isEmpty(), CollaborationRoomRow::getTitle, literal);
    }

    private List<EmptyRoom> emptyRooms(AuthContext actor, String keyword, PagePosition after, int limit) {
        var query = roomSearch(actor, keyword).isNull(CollaborationRoomRow::getCurrentTaskId)
            .orderByDesc(CollaborationRoomRow::getUpdatedAt, CollaborationRoomRow::getId);
        if (after != null) {
            boolean includeSameId = "task".equals(after.sortValue());
            query.and(q -> q.lt(CollaborationRoomRow::getUpdatedAt, after.time()).or(equal -> equal.eq(CollaborationRoomRow::getUpdatedAt, after.time())
                .le(includeSameId, CollaborationRoomRow::getId, after.id()).lt(!includeSameId, CollaborationRoomRow::getId, after.id())));
        }
        var rows = rooms.selectPage(new Page<>(1, limit + 1, false), query).getRecords();
        if (rows.isEmpty()) {
            return List.of();
        }
        var names = roomMembers.selectList(new LambdaQueryWrapper<CollaborationRoomMemberRow>()
            .eq(CollaborationRoomMemberRow::getEnterpriseId, actor.enterpriseId()).in(CollaborationRoomMemberRow::getRoomId, rows.stream().map(CollaborationRoomRow::getId).toList())
            .orderByAsc(CollaborationRoomMemberRow::getSortOrder)).stream().collect(Collectors.groupingBy(CollaborationRoomMemberRow::getRoomId,
                Collectors.mapping(CollaborationRoomMemberRow::getDisplayName, Collectors.toList())));
        return rows.stream().map(room -> new EmptyRoom(room.getId(), room.getTitle(), room.getUpdatedAt(), names.getOrDefault(room.getId(), List.of()))).toList();
    }

    public record EmptyRoom(String id, String title, Instant updatedAt, List<String> members) {
    }

    public record DirectoryEntry(CollaborationTaskSummary task, EmptyRoom room) {
        PagePosition position() {
            return task == null ? new PagePosition(room.updatedAt(), room.id(), "room")
                : new PagePosition(Instant.parse(task.updatedAt()), task.id(), "task");
        }
    }

    private List<Match> findTasks(AuthContext actor, String conversation, String selectedStatus, String keyword,
            PagePosition after, int limit, boolean includeRoomNames) {
        var matches = new ArrayList<Match>();
        int batchSize = keyword.isBlank() ? limit + 1 : 100;
        while (matches.size() <= limit) {
            var query = new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId()).eq(conversation != null, CollaborationTaskRow::getConversationId, conversation)
                .eq(selectedStatus != null, CollaborationTaskRow::getStatus, selectedStatus).orderByDesc(CollaborationTaskRow::getUpdatedAt, CollaborationTaskRow::getId);
            if (after != null) {
                var position = after;
                query.and(value -> value.lt(CollaborationTaskRow::getUpdatedAt, position.time()).or(equal -> equal
                    .eq(CollaborationTaskRow::getUpdatedAt, position.time()).lt(CollaborationTaskRow::getId, position.id())));
            }
            var batch = tasks.selectPage(new Page<CollaborationTaskRow>(1, batchSize, false), query).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            var requirements = display.requirements(actor, batch.stream().map(task -> cipher.decrypt(actor.enterpriseId(),
                "task-objective", task.getId(), task.getObjectiveEncrypted())).toList());
            Set<String> namedRooms = Set.of();
            if (includeRoomNames && !keyword.isBlank()) {
                var ids = batch.stream().map(CollaborationTaskRow::getWebRoomId).filter(Objects::nonNull).distinct().toList();
                if (!ids.isEmpty()) {
                    namedRooms = rooms.selectList(roomSearch(actor, keyword).select(CollaborationRoomRow::getId).in(CollaborationRoomRow::getId, ids))
                        .stream().map(CollaborationRoomRow::getId).collect(Collectors.toSet());
                }
            }
            for (int index = 0; index < batch.size() && matches.size() <= limit; index++) {
                var task = batch.get(index);
                var content = requirements.get(index);
                if (keyword.isBlank() || task.getShortCode().toLowerCase(Locale.ROOT).contains(keyword)
                    || content.path("text").asText().toLowerCase(Locale.ROOT).contains(keyword)
                    || task.getWebRoomId() != null && namedRooms.contains(task.getWebRoomId())) {
                    matches.add(new Match(task, content));
                }
            }
            if (batch.size() < batchSize || keyword.isBlank()) {
                break;
            }
            var last = batch.getLast();
            after = new PagePosition(last.getUpdatedAt(), last.getId());
        }
        return matches;
    }

    private List<CollaborationTaskSummary> summaries(AuthContext actor, List<Match> matches) {
        if (matches.isEmpty()) {
            return List.of();
        }
        var sourceIds = matches.stream().map(match -> match.task().getConversationId()).filter(Objects::nonNull).distinct().toList();
        var sources = sourceIds.isEmpty() ? Map.<String, ChatConversationRow>of() : conversations.selectList(new LambdaQueryWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, actor.enterpriseId())
            .in(ChatConversationRow::getId, sourceIds)).stream().collect(Collectors.toMap(ChatConversationRow::getId, Function.identity()));
        var roomIds = matches.stream().map(match -> match.task().getWebRoomId()).filter(Objects::nonNull).distinct().toList();
        var roomNames = roomIds.isEmpty() ? Map.<String, String>of() : rooms.selectList(new LambdaQueryWrapper<CollaborationRoomRow>()
            .select(CollaborationRoomRow::getId, CollaborationRoomRow::getTitle).eq(CollaborationRoomRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationRoomRow::getOwnerUserId, actor.userId()).in(CollaborationRoomRow::getId, roomIds)).stream()
            .collect(Collectors.toMap(CollaborationRoomRow::getId, CollaborationRoomRow::getTitle));
        var taskIds = matches.stream().map(match -> match.task().getId()).toList();
        var roles = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId()).in(CollaborationParticipantRow::getTaskId, taskIds)
            .orderByAsc(CollaborationParticipantRow::getCreatedAt, CollaborationParticipantRow::getId)).stream()
            .collect(Collectors.groupingBy(CollaborationParticipantRow::getTaskId));
        return matches.stream().map(match -> {
            var task = match.task();
            var source = task.fromWebRoom() ? null : sources.get(task.getConversationId());
            var roster = roles.getOrDefault(task.getId(), List.of());
            var ordered = roster.stream().sorted((left, right) -> Boolean.compare(
                right.getId().equals(task.getOwnerParticipantId()), left.getId().equals(task.getOwnerParticipantId()))).toList();
            var summaries = ordered.stream().map(role -> new CollaborationTaskSummary.Role(role.getId(), role.getRoleName(), role.getConversationId())).toList();
            String primary = ordered.stream().map(CollaborationParticipantRow::getConversationId).filter(Objects::nonNull).findFirst().orElse(null);
            String name = source == null || Messages.source("text.collaboration.collaborationTaskListService.summaries.1").equals(source.getName()) ? null : source.getName();
            var origin = task.fromWebRoom() ? new CollaborationTaskSummary.Source("web", "room", roomNames.get(task.getWebRoomId()))
                : source == null ? null : new CollaborationTaskSummary.Source(source.getPlatformCode(), source.getConversationKind(), name);
            return CollaborationTaskViewMapper.summary(task, match.requirements(), origin, summaries, primary);
        }).toList();
    }

    private record Match(CollaborationTaskRow task, JsonNode requirements) {
    }
}
