package com.stonewu.agenteam.service.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.ConversationMapper;
import com.stonewu.agenteam.mapper.execution.ConversationNavigationMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceVersionSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.model.execution.response.ConversationNavigationItem;
import com.stonewu.agenteam.model.execution.response.ConversationNavigationItem.Group;
import com.stonewu.agenteam.model.execution.response.ConversationNavigationItem.Role;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.collaboration.CollaborationMessageDisplayService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 导航按任务组织，读取消息、执行和文件继续使用原岗位对话。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ConversationNavigationService {
    private final ConversationNavigationMapper navigation;
    private final ConversationMapper conversations;
    private final ConversationSqlMapper rows;
    private final ConversationQueryService queries;
    private final CollaborationParticipantMapper participants;
    private final CollaborationTaskMapper tasks;
    private final CollaborationWorkItemMapper works;
    private final ChatPayloadCipher cipher;
    private final CollaborationMessageDisplayService display;
    private final EnterpriseAuthorizationService authorization;
    private final ListPagination pagination;
    private final Clock clock;
    private final ResourceVersionSqlMapper versions;
    private final ResourceJson json;

    public ConversationNavigationService(ConversationNavigationMapper navigation, ConversationMapper conversations,
        ConversationSqlMapper rows, ConversationQueryService queries, CollaborationParticipantMapper participants,
        CollaborationTaskMapper tasks, CollaborationWorkItemMapper works, ChatPayloadCipher cipher,
        CollaborationMessageDisplayService display, EnterpriseAuthorizationService authorization,
        ListPagination pagination, Clock clock, ResourceVersionSqlMapper versions, ResourceJson json) {
        this.navigation = navigation;
        this.conversations = conversations;
        this.rows = rows;
        this.queries = queries;
        this.participants = participants;
        this.tasks = tasks;
        this.works = works;
        this.cipher = cipher;
        this.display = display;
        this.authorization = authorization;
        this.pagination = pagination;
        this.clock = clock;
        this.versions = versions;
        this.json = json;
    }

    public PageResponse<ConversationNavigationItem> list(AuthContext actor, String query, String status,
        Boolean favorite, String agentId, String scope, String cursor, Integer limit) {
        String state = status == null ? "active" : status;
        if (!Set.of("active", "archived", "deleted").contains(state)) {
            throw ApiException.invalidField("status", "请选择有效的对话状态。");
        }
        return page(actor, query, List.of(state), favorite, agentId, scope, cursor, limit);
    }

    public List<ConversationNavigationItem> search(AuthContext actor, String query) {
        return page(actor, query, List.of("active", "archived"), null, null, "all", null, 10).items();
    }

    private PageResponse<ConversationNavigationItem> page(AuthContext actor, String query, List<String> states,
        Boolean favorite, String agentId, String scope, String cursor, Integer requested) {
        authorization.require(actor, "conversation.view");
        String kind = scope == null ? "all" : scope;
        if (!Set.of("all", "standalone", "collaboration").contains(kind)) {
            throw ApiException.invalidField("scope", "请选择有效的对话类型。");
        }
        String text = pagination.query(query);
        String employee = agentId == null || agentId.isBlank() ? null : agentId;
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "conversation-navigation",
            text + "|" + states + "|" + favorite + "|" + employee + "|" + kind, "updated_desc");
        var after = pagination.read(cursor, binding);
        var found = new ArrayList<ConversationNavigationItem>();
        var availability = new HashMap<String, String>();
        while (found.size() <= limit) {
            var candidates = navigation.list(actor.enterpriseId(), actor.userId(), states, favorite, employee,
                text, kind, after, 100, clock.instant().minus(Duration.ofDays(30)));
            if (candidates.isEmpty()) {
                break;
            }
            var groups = groups(actor, candidates.stream().map(value -> value.getTaskId()).filter(Objects::nonNull).distinct().toList());
            for (var row : candidates) {
                var group = row.getTaskId() == null ? null : groups.get(row.getTaskId());
                if (row.getTaskId() != null && group == null) {
                    continue;
                }
                if (group != null && !matches(group.group(), text)) {
                    continue;
                }
                availability.put("collaboration:" + row.getId(), row.getTaskId());
                var conversation = queries.view(actor, conversations.map(row), availability);
                found.add(new ConversationNavigationItem(row.getEntryKey(), group == null ? row.getTitle() : group.group().title(),
                    row.getGroupUpdatedAt().toString(), Boolean.TRUE.equals(row.getGroupFavorite()), conversation,
                    group == null ? null : group.group(), employee != null || !text.isEmpty() || Boolean.TRUE.equals(favorite)));
                if (found.size() > limit) {
                    break;
                }
            }
            var last = candidates.getLast();
            after = new PagePosition(last.getGroupUpdatedAt(), last.getEntryKey(), null);
            if (candidates.size() < 100) {
                break;
            }
        }
        return pagination.page(found, limit, binding,
            value -> new PagePosition(Instant.parse(value.updatedAt()), value.key(), null));
    }

    public ConversationNavigationItem current(AuthContext actor, String conversationId) {
        var row = queries.readable(actor, conversationId);
        var view = queries.view(actor, row, new HashMap<>());
        var data = view.collaborationTaskId() == null ? null : groups(actor, List.of(view.collaborationTaskId())).get(view.collaborationTaskId());
        return new ConversationNavigationItem(data == null ? "conversation:" + row.id() : "task:" + data.group().taskId(),
            data == null ? row.title() : data.group().title(), data == null ? row.updatedAt().toString() : data.updatedAt().toString(),
            data == null ? row.favorite() : data.favorite(), view, data == null ? null : data.group(), false);
    }

    private record GroupData(Group group, boolean favorite, Instant updatedAt) {
    }

    private Map<String, GroupData> groups(AuthContext actor, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var owned = tasks.selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationTaskRow::getInitiatorUserId, actor.userId())
            .in(CollaborationTaskRow::getId, ids));
        if (owned.isEmpty()) {
            return Map.of();
        }
        var taskIds = owned.stream().map(CollaborationTaskRow::getId).toList();
        var roles = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId()).in(CollaborationParticipantRow::getTaskId, taskIds)
            .orderByAsc(CollaborationParticipantRow::getCreatedAt, CollaborationParticipantRow::getId));
        var conversationIds = roles.stream().map(CollaborationParticipantRow::getConversationId).filter(Objects::nonNull).toList();
        var versionIds = roles.stream().map(CollaborationParticipantRow::getAgentVersionId).filter(Objects::nonNull).distinct().toList();
        var appearances = versionIds.isEmpty() ? Map.<String, ResourceVersionRow>of() : versions.selectList(new LambdaQueryWrapper<ResourceVersionRow>()
            .eq(ResourceVersionRow::getEnterpriseId, actor.enterpriseId()).in(ResourceVersionRow::getId, versionIds))
            .stream().collect(Collectors.toMap(ResourceVersionRow::getId, value -> value));
        var readable = conversationIds.isEmpty() ? Map.<String, AgentConversationRow>of() : rows.selectList(new LambdaQueryWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId()).eq(AgentConversationRow::getUserId, actor.userId())
            .ne(AgentConversationRow::getStatus, "deleted").in(AgentConversationRow::getId, conversationIds)).stream()
            .collect(Collectors.toMap(AgentConversationRow::getId, value -> value));
        var workByRole = works.selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId()).in(CollaborationWorkItemRow::getTaskId, taskIds)
            .ne(CollaborationWorkItemRow::getStatus, "superseded").orderByDesc(CollaborationWorkItemRow::getCreatedAt, CollaborationWorkItemRow::getId))
            .stream().collect(Collectors.groupingBy(CollaborationWorkItemRow::getParticipantId));
        var requirements = display.requirements(actor, owned.stream().map(task ->
            cipher.decrypt(actor.enterpriseId(), "task-objective", task.getId(), task.getObjectiveEncrypted())).toList());
        var result = new HashMap<String, GroupData>();
        for (int index = 0; index < owned.size(); index++) {
            var task = owned.get(index);
            String text = requirements.get(index).path("text").asText().replaceAll("\\s+", " ").trim();
            String title = text.isEmpty() ? "协作任务" : text.substring(0, text.offsetByCodePoints(0, Math.min(100, text.codePointCount(0, text.length()))));
            var taskRoles = roles.stream().filter(role -> role.getTaskId().equals(task.getId()))
                .sorted(Comparator.comparing(role -> !role.getId().equals(task.getOwnerParticipantId()))).map(role -> {
                var currentWork = workByRole.getOrDefault(role.getId(), List.of()).stream()
                    .filter(work -> Objects.equals(work.getInputRevision(), task.getInputRevision())).findFirst().orElse(null);
                var version = appearances.get(role.getAgentVersionId());
                var config = version == null ? null : json.read(version.getConfigJson());
                return new Role(role.getId(), role.getRoleName(), role.getConversationId() != null && readable.containsKey(role.getConversationId()) ? role.getConversationId() : null,
                    role.getAgentId(), currentWork == null ? "ready" : currentWork.getStatus(),
                    config == null ? null : config.path("icon").asText(null), config == null ? null : config.path("color").asText(null));
            }).toList();
            String owner = taskRoles.stream().filter(role -> role.id().equals(task.getOwnerParticipantId())).map(Role::conversationId)
                .filter(Objects::nonNull).findFirst().orElse(null);
            var taskConversations = taskRoles.stream().map(Role::conversationId).filter(Objects::nonNull).map(readable::get).toList();
            result.put(task.getId(), new GroupData(new Group(task.getId(), task.getShortCode(), title, task.getStatus(), owner, taskRoles),
                taskConversations.stream().anyMatch(value -> Integer.valueOf(1).equals(value.getFavorite())),
                taskConversations.stream().map(AgentConversationRow::getUpdatedAt).max(Comparator.naturalOrder()).orElse(task.getUpdatedAt())));
        }
        return result;
    }

    private boolean matches(Group group, String query) {
        String text = query.toLowerCase(Locale.ROOT);
        return text.isEmpty() || group.title().toLowerCase(Locale.ROOT).contains(text)
            || group.shortCode().toLowerCase(Locale.ROOT).contains(text)
            || group.roles().stream().anyMatch(role -> role.name().toLowerCase(Locale.ROOT).contains(text));
    }
}
