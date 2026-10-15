package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatConversationMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskViewMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatConversationRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskSummary;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

    public CollaborationTaskListService(CollaborationTaskMapper tasks, CollaborationParticipantMapper participants,
            ChatConversationMapper conversations, ChatIdentityService identities, ChatPayloadCipher cipher,
            ResourceJson json, CollaborationMessageDisplayService display, ListPagination pagination) {
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
            throw ApiException.invalidField("status", "请选择有效的任务状态。");
        }
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "collaboration/tasks",
            json.write(json.tree(List.of(conversation == null ? "" : conversation, selectedStatus == null ? "" : selectedStatus, keyword))), "updated_desc");
        var after = pagination.read(cursor, binding);
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
            for (int index = 0; index < batch.size() && matches.size() <= limit; index++) {
                var task = batch.get(index);
                var content = requirements.get(index);
                if (keyword.isBlank() || task.getShortCode().toLowerCase(Locale.ROOT).contains(keyword)
                    || content.path("text").asText().toLowerCase(Locale.ROOT).contains(keyword)) {
                    matches.add(new Match(task, content));
                }
            }
            if (batch.size() < batchSize || keyword.isBlank()) {
                break;
            }
            var last = batch.getLast();
            after = new PagePosition(last.getUpdatedAt(), last.getId());
        }
        var page = pagination.page(matches, limit, binding, match -> new PagePosition(match.task().getUpdatedAt(), match.task().getId()));
        if (page.items().isEmpty()) {
            return new PageResponse<>(List.of(), page.nextCursor(), page.hasMore());
        }
        var sourceIds = page.items().stream().map(match -> match.task().getConversationId()).distinct().toList();
        var sources = conversations.selectList(new LambdaQueryWrapper<ChatConversationRow>().eq(ChatConversationRow::getEnterpriseId, actor.enterpriseId())
            .in(ChatConversationRow::getId, sourceIds)).stream().collect(Collectors.toMap(ChatConversationRow::getId, Function.identity()));
        var taskIds = page.items().stream().map(match -> match.task().getId()).toList();
        var roles = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId()).in(CollaborationParticipantRow::getTaskId, taskIds)
            .orderByAsc(CollaborationParticipantRow::getCreatedAt, CollaborationParticipantRow::getId)).stream()
            .collect(Collectors.groupingBy(CollaborationParticipantRow::getTaskId));
        var items = page.items().stream().map(match -> {
            var task = match.task();
            var source = sources.get(task.getConversationId());
            var roster = roles.getOrDefault(task.getId(), List.of());
            var ordered = roster.stream().sorted((left, right) -> Boolean.compare(
                right.getId().equals(task.getOwnerParticipantId()), left.getId().equals(task.getOwnerParticipantId()))).toList();
            var summaries = ordered.stream().map(role -> new CollaborationTaskSummary.Role(role.getId(), role.getRoleName(), role.getConversationId())).toList();
            String primary = ordered.stream().map(CollaborationParticipantRow::getConversationId).filter(Objects::nonNull).findFirst().orElse(null);
            String name = source == null || "尚未取得群名称".equals(source.getName()) ? null : source.getName();
            var origin = source == null ? null : new CollaborationTaskSummary.Source(source.getPlatformCode(), source.getConversationKind(), name);
            return CollaborationTaskViewMapper.summary(task, match.requirements(), origin, summaries, primary);
        }).toList();
        return new PageResponse<>(items, page.nextCursor(), page.hasMore());
    }

    private record Match(CollaborationTaskRow task, JsonNode requirements) {
    }
}
