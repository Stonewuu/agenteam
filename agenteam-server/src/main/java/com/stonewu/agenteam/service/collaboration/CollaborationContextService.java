package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.configuration.collaboration.CollaborationConfiguration;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationContextMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationContextRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatRecords;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;

/** 每轮保存不可变背景，不共享其他岗位的系统提示词、推理或私聊历史。 */
@Service
public class CollaborationContextService {
    private final CollaborationContextMapper contexts;
    private final CollaborationArtifactMapper artifacts;
    private final CollaborationParticipantMapper participants;
    private final ChatMessageMapper messages;
    private final CollaborationAccessService access;
    private final CollaborationArtifactService results;
    private final ChatPayloadCipher cipher;
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final CollaborationConfiguration configuration;
    private final Clock clock;
    private final CollaborationDependencyService dependencies;

    public CollaborationContextService(CollaborationContextMapper contexts, CollaborationArtifactMapper artifacts,
            CollaborationParticipantMapper participants, ChatMessageMapper messages, CollaborationAccessService access,
            CollaborationArtifactService results, ChatPayloadCipher cipher, ResourceJson json, ObjectMapper mapper,
            CollaborationConfiguration configuration, Clock clock, CollaborationDependencyService dependencies) {
        this.contexts = contexts;
        this.artifacts = artifacts;
        this.participants = participants;
        this.messages = messages;
        this.access = access;
        this.results = results;
        this.cipher = cipher;
        this.json = json;
        this.mapper = mapper;
        this.configuration = configuration;
        this.clock = clock;
        this.dependencies = dependencies;
    }

    /** 本方法只读取本地固定数据，不调用模型或平台网络，调用方持有企业事务锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public CollaborationContextRow freeze(CollaborationTaskRow task, CollaborationWorkItemRow work, int round) {
        access.requireRole(task, access.participant(task, work.getParticipantId()));
        var existing = contexts.selectOne(new LambdaQueryWrapper<CollaborationContextRow>().eq(CollaborationContextRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationContextRow::getWorkId, work.getId()).eq(CollaborationContextRow::getRunRound, round));
        if (existing != null) {
            if (!existing.getInputRevision().equals(task.getInputRevision())) {
                throw new ApiException(HttpStatus.CONFLICT, "TASK_INPUT_CHANGED", "已固定的上下文不能替换为另一版要求。");
            }
            return existing;
        }
        var root = mapper.createObjectNode().put("schemaVersion", 1);
        var taskData = root.putObject("task").put("id", task.getId()).put("shortCode", task.getShortCode())
            .put("inputRevision", task.getInputRevision()).put("ownerRoleId", task.getOwnerParticipantId()).put("ownerWorkId", task.getOwnerWorkId());
        taskData.set("requirements", cipher.decrypt(task.getEnterpriseId(), "task-objective", task.getId(), task.getObjectiveEncrypted()));
        taskData.set("waivedRoleIds", json.read(task.getWaivedRequestedRolesJson()));
        var assignment = root.putObject("assignment").put("id", work.getId()).put("participantId", work.getParticipantId());
        assignment.set("instruction", cipher.decrypt(task.getEnterpriseId(), "work-instruction", work.getId(), work.getInstructionEncrypted()));
        root.putObject("outputPolicy").put("visibility", task.getOutputVisibility());
        var roster = root.putArray("allowedRoles");
        for (var role : participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, task.getEnterpriseId()).eq(CollaborationParticipantRow::getTaskId, task.getId()))) {
            roster.addObject().put("id", role.getId()).put("name", role.getRoleName()).put("description", role.getDescriptionSnapshot())
                .put("versionId", role.getAgentVersionId()).put("explicitlyRequested", role.getExplicitlyRequested()).put("availability", role.getAvailability());
        }
        requireSize(root);
        var references = mapper.createArrayNode();
        var summaries = root.putArray("artifacts");
        var included = new HashSet<String>();
        var required = new LinkedHashMap<String, CollaborationArtifactRow>();
        results.references(task, assignment.path("instruction").path("artifactRefs")).forEach(result -> required.put(result.getId(), result));
        var prerequisiteIds = new HashSet<String>();
        dependencies.edges(task).stream().filter(edge -> edge.getWorkId().equals(work.getId())).forEach(edge -> prerequisiteIds.add(edge.getDependsOnWorkId()));
        for (var prerequisite : dependencies.works(task)) {
            if (prerequisiteIds.contains(prerequisite.getId())) {
                results.workResults(task, prerequisite).forEach(result -> required.put(result.getId(), result));
            }
        }
        var inputArtifacts = assignment.putArray("inputArtifactRefs");
        for (var result : required.values()) {
            summaries.add(results.describe(result));
            references.addObject().put("artifactId", result.getId()).put("version", result.getArtifactVersion()).put("sha256", result.getSha256());
            inputArtifacts.addObject().put("artifactId", result.getId()).put("version", result.getArtifactVersion());
            included.add(result.getId());
        }
        requireSize(root);
        for (var result : artifacts.selectList(new LambdaQueryWrapper<CollaborationArtifactRow>().eq(CollaborationArtifactRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationArtifactRow::getTaskId, task.getId()).eq(CollaborationArtifactRow::getStatus, "ready")
            .orderByDesc(CollaborationArtifactRow::getCreatedAt, CollaborationArtifactRow::getId))) {
            if (included.contains(result.getId())) {
                continue;
            }
            var description = results.describe(result);
            if (characters(root) + characters(description) > configuration.getMaxContextCharacters()) {
                continue;
            }
            summaries.add(description);
            references.addObject().put("artifactId", result.getId()).put("version", result.getArtifactVersion()).put("sha256", result.getSha256());
        }
        var selected = root.putArray("messages");
        int omitted = 0;
        for (var message : messages.selectList(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, task.getEnterpriseId())
            .eq(ChatMessageRow::getTaskId, task.getId()).eq(ChatMessageRow::getSenderUserId, task.getInitiatorUserId())
            .orderByDesc(ChatMessageRow::getPlatformCreatedAt, ChatMessageRow::getId))) {
            var content = cipher.decrypt(task.getEnterpriseId(), "message", message.getId(), message.getContentEncrypted());
            var item = mapper.createObjectNode().put("messageId", message.getId()).put("text", content.path("text").asText())
                .put("createdAt", message.getPlatformCreatedAt().toString());
            if (selected.size() >= configuration.getMaxContextMessages() || characters(root) + characters(item) + 200 > configuration.getMaxContextCharacters()) {
                omitted++;
                continue;
            }
            selected.add(item);
        }
        root.putObject("sourceCoverage").put("receivedMentionMessages", true).put("fullGroupHistoryAvailable", false).put("omittedMessageCount", omitted);
        requireSize(root);
        var row = ChatRecords.initialize(new CollaborationContextRow(), task.getEnterpriseId(), clock.instant());
        row.setTaskId(task.getId());
        row.setWorkId(work.getId());
        row.setRunRound(round);
        row.setInputRevision(task.getInputRevision());
        row.setSchemaVersion(1);
        row.setContentEncrypted(cipher.encrypt(task.getEnterpriseId(), "context", row.getId(), root));
        row.setContentHash(json.hash(root));
        row.setSourceRefsJson(json.write(references));
        row.setSizeChars(characters(root));
        row.setOmittedMessageCount(omitted);
        contexts.insert(row);
        return row;
    }

    public JsonNode read(CollaborationTaskRow task, String id) {
        var row = contexts.selectOne(new LambdaQueryWrapper<CollaborationContextRow>().eq(CollaborationContextRow::getEnterpriseId, task.getEnterpriseId())
            .eq(CollaborationContextRow::getTaskId, task.getId()).eq(CollaborationContextRow::getId, id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COLLABORATION_CONTEXT_REQUIRED", "当前岗位没有可读取的固定上下文。");
        }
        access.actor(task);
        return cipher.decrypt(task.getEnterpriseId(), "context", id, row.getContentEncrypted());
    }

    private void requireSize(ObjectNode node) {
        if (characters(node) > configuration.getMaxContextCharacters()) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_CONTEXT_TOO_LARGE", "任务要求和交接内容过长，请拆分任务后继续。");
        }
    }

    private int characters(JsonNode node) {
        var text = node.toString();
        return text.codePointCount(0, text.length());
    }
}
