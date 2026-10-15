package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationActionMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskViewMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskDetail;
import com.stonewu.agenteam.model.collaboration.response.CollaborationTaskSummary;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatConversationService;
import com.stonewu.agenteam.service.chat.ChatReplyPolicy;
import com.stonewu.agenteam.service.http.ListPagination;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理员权限不扩大任务正文读取范围，所有查询固定到原发起人。 */
@Service
public class CollaborationQueryService {
    private final CollaborationTaskListService taskList;
    private final CollaborationParticipantMapper participants;
    private final CollaborationWorkItemMapper works;
    private final CollaborationArtifactMapper artifactRows;
    private final CollaborationActionMapper actions;
    private final ChatMessageMapper messages;
    private final ChatMessageResourceMapper resources;
    private final ChatOutboundMessageMapper outgoing;
    private final CollaborationAccessService access;
    private final ChatIdentityService identities;
    private final CollaborationArtifactService artifacts;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final ListPagination pagination;
    private final ChatConversationService conversations;
    private final CollaborationMessageDisplayService display;

    public CollaborationQueryService(CollaborationTaskListService taskList, CollaborationParticipantMapper participants,
            CollaborationWorkItemMapper works, CollaborationArtifactMapper artifactRows, CollaborationActionMapper actions,
            ChatMessageMapper messages, ChatMessageResourceMapper resources, ChatOutboundMessageMapper outgoing,
            CollaborationAccessService access, ChatIdentityService identities, CollaborationArtifactService artifacts,
            ChatPayloadCipher cipher, ResourceJson json, ListPagination pagination, ChatConversationService conversations,
            CollaborationMessageDisplayService display) {
        this.taskList = taskList;
        this.participants = participants;
        this.works = works;
        this.artifactRows = artifactRows;
        this.actions = actions;
        this.messages = messages;
        this.resources = resources;
        this.outgoing = outgoing;
        this.access = access;
        this.identities = identities;
        this.artifacts = artifacts;
        this.cipher = cipher;
        this.json = json;
        this.pagination = pagination;
        this.conversations = conversations;
        this.display = display;
    }

    public PageResponse<CollaborationTaskSummary> list(AuthContext actor, String conversation, String status, String cursor, Integer requested) {
        return taskList.list(actor, conversation, status, null, cursor, requested);
    }

    public PageResponse<CollaborationTaskSummary> list(AuthContext actor, String conversation, String status, String query, String cursor, Integer requested) {
        return taskList.list(actor, conversation, status, query, cursor, requested);
    }

    public CollaborationTaskDetail detail(AuthContext actor, String id) {
        var task = access.owned(actor, id);
        var roles = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>().eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationParticipantRow::getTaskId, id).orderByAsc(CollaborationParticipantRow::getCreatedAt, CollaborationParticipantRow::getId)).stream()
            .map(role -> new CollaborationTaskDetail.Role(role.getId(), role.getBotBindingId(), role.getRoleName(), role.getDescriptionSnapshot(),
                role.getAgentId(), role.getAgentVersionId(), role.getExplicitlyRequested(), role.getAvailability(), role.getConversationId())).toList();
        var work = works.selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationWorkItemRow::getTaskId, id).orderByAsc(CollaborationWorkItemRow::getCreatedAt, CollaborationWorkItemRow::getId)).stream()
            .map(row -> new CollaborationTaskDetail.Work(row.getId(), row.getParticipantId(), row.getPurpose(), row.getStatus(), row.getRequired(),
                row.getInputRevision(), row.getRunRound(), row.getActiveRunId(), row.getErrorCode(), row.getResultArtifactId(), row.getReplacementWorkId())).toList();
        var results = artifactRows.selectList(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationArtifactRow::getTaskId, id).eq(CollaborationArtifactRow::getStatus, "ready")
            .orderByDesc(CollaborationArtifactRow::getCreatedAt, CollaborationArtifactRow::getId)).stream().map(artifacts::describe).map(json::object).toList();
        var questions = actions.selectList(new LambdaQueryWrapper<CollaborationActionRow>().eq(CollaborationActionRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationActionRow::getTaskId, id).eq(CollaborationActionRow::getActionType, "ask_user")
            .orderByDesc(CollaborationActionRow::getSequence)).stream().map(this::question).toList();
        var deliveries = outgoing.selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatOutboundMessageRow::getTaskId, id).orderByAsc(ChatOutboundMessageRow::getTaskSequence)).stream()
            .map(row -> new CollaborationTaskDetail.Delivery(row.getId(), row.getBotBindingId(), row.getKind(), row.getStatus(), row.getErrorCode(), time(row.getCreatedAt()))).toList();
        var requirements = display.requirements(actor, List.of(requirements(task))).getFirst();
        return new CollaborationTaskDetail(summary(task, requirements), json.object(requirements),
            task.getOwnerWorkId(), task.getOwnerParticipantId(), roles, work, results, questions, deliveries,
            ChatReplyPolicy.allowsFullResult(task, conversations.require(actor.enterpriseId(), task.getConversationId())));
    }

    public PageResponse<Map<String, Object>> messages(AuthContext actor, String task, String cursor, Integer requested) {
        access.owned(actor, task);
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "collaboration/messages", task, "created_desc");
        var after = pagination.read(cursor, binding);
        var query = new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId()).eq(ChatMessageRow::getTaskId, task)
            .eq(ChatMessageRow::getSenderUserId, actor.userId()).orderByDesc(ChatMessageRow::getPlatformCreatedAt, ChatMessageRow::getId);
        if (after != null) {
            query.and(value -> value.lt(ChatMessageRow::getPlatformCreatedAt, after.time()).or(equal -> equal.eq(ChatMessageRow::getPlatformCreatedAt, after.time())
                .lt(ChatMessageRow::getId, after.id())));
        }
        var page = pagination.page(messages.selectPage(new Page<ChatMessageRow>(1, limit + 1, false), query).getRecords(), limit,
            binding, message -> new PagePosition(message.getPlatformCreatedAt(), message.getId()));
        var ids = page.items().stream().map(ChatMessageRow::getId).toList();
        var files = ids.isEmpty() ? List.<ChatMessageResourceRow>of() : resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, actor.enterpriseId()).in(ChatMessageResourceRow::getMessageId, ids));
        var grouped = files.stream().collect(Collectors.groupingBy(ChatMessageResourceRow::getMessageId));
        var mapped = page.items().stream().map(message -> json.tree(Map.of("id", message.getId(), "type", message.getMessageType(), "text",
            cipher.decrypt(actor.enterpriseId(), "message", message.getId(), message.getContentEncrypted()).path("text").asText(),
            "createdAt", time(message.getPlatformCreatedAt()), "attachments", grouped.getOrDefault(message.getId(), List.of()).stream().map(file ->
                Map.of("id", file.getId(), "fileId", file.getFileId() == null ? "" : file.getFileId(), "status", file.getStatus(), "sizeBytes", file.getSizeBytes())).toList()))).toList();
        return new PageResponse<>(mapped.stream().map(json::object).toList(), page.nextCursor(), page.hasMore());
    }

    public Map<String, Object> artifact(AuthContext actor, String task, String artifact, int version, int offset, int limit) {
        return json.object(artifacts.read(access.owned(actor, task), artifact, version, offset, limit));
    }

    private CollaborationTaskDetail.Question question(CollaborationActionRow action) {
        var content = cipher.decrypt(action.getEnterpriseId(), "action", action.getId(), action.getPayloadEncrypted());
        var options = new ArrayList<String>();
        content.path("options").forEach(value -> options.add(value.asText()));
        return new CollaborationTaskDetail.Question(action.getId(), action.getWorkId(), action.getInputRevision(), content.path("question").asText(), options,
            time(action.getCreatedAt()));
    }

    private JsonNode requirements(CollaborationTaskRow task) {
        return cipher.decrypt(task.getEnterpriseId(), "task-objective", task.getId(), task.getObjectiveEncrypted());
    }

    private CollaborationTaskSummary summary(CollaborationTaskRow task, JsonNode requirements) {
        return CollaborationTaskViewMapper.summary(task, requirements);
    }

    private String time(Instant value) {
        return value == null ? null : value.toString();
    }
}
