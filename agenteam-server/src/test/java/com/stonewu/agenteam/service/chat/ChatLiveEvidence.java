package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.chat.ChatBotBindingMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.chat.ChatInboundReceiptMapper;
import com.stonewu.agenteam.mapper.chat.ChatOutboundMessageMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationActionMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.execution.entity.ExecutionLimits;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatInboundReceiptRow;
import com.stonewu.agenteam.model.chat.entity.ChatOutboundMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationActionRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.support.MybatisTestDatabase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** 联调过程只导出状态和关联编号，不导出凭据、消息正文及其密文。 */
final class ChatLiveEvidence {
    private final MybatisTestDatabase database;
    private final RunMapper runs;
    private final ObjectMapper json;
    private final String enterprise;
    private final Path directory;

    ChatLiveEvidence(MybatisTestDatabase database, RunMapper runs, ObjectMapper json, String enterprise, Path directory) {
        this.database = database;
        this.runs = runs;
        this.json = json;
        this.enterprise = enterprise;
        this.directory = directory;
    }

    void save() throws IOException {
        var state = json.createObjectNode().put("checkedAt", Instant.now().toString()).put("enterprise", enterprise);
        var bots = state.putArray("bots");
        for (var value : database.mapper(ChatBotBindingMapper.class).selectList(new LambdaQueryWrapper<ChatBotBindingRow>()
                .eq(ChatBotBindingRow::getEnterpriseId, enterprise))) {
            bots.addObject().put("id", value.getId()).put("role", value.getRoleName()).put("status", value.getStatus())
                .put("connectedAt", value.getLastConnectedAt() == null ? null : value.getLastConnectedAt().toString());
        }
        var receipts = state.putArray("receipts");
        for (var value : database.mapper(ChatInboundReceiptMapper.class).selectList(new LambdaQueryWrapper<ChatInboundReceiptRow>()
                .eq(ChatInboundReceiptRow::getEnterpriseId, enterprise))) {
            receipts.addObject().put("id", value.getId()).put("botId", value.getBotBindingId()).put("status", value.getStatus())
                .put("eventType", value.getEventType()).put("platformMessageId", value.getPlatformMessageId())
                .put("messageId", value.getMessageId()).put("attempts", value.getAttemptCount()).put("errorCode", value.getErrorCode());
        }
        var messages = state.putArray("messages");
        for (var value : database.mapper(ChatMessageMapper.class).selectList(new LambdaQueryWrapper<ChatMessageRow>()
                .eq(ChatMessageRow::getEnterpriseId, enterprise))) {
            messages.addObject().put("id", value.getId()).put("platformId", value.getPlatformMessageId()).put("type", value.getMessageType())
                .put("senderKind", value.getSenderKind()).put("routingStatus", value.getRoutingStatus()).put("taskId", value.getTaskId())
                .put("threadId", value.getThreadId()).put("replyTo", value.getReplyToPlatformId());
        }
        var resources = state.putArray("resources");
        for (var value : database.mapper(ChatMessageResourceMapper.class).selectList(new LambdaQueryWrapper<ChatMessageResourceRow>()
                .eq(ChatMessageResourceRow::getEnterpriseId, enterprise))) {
            resources.addObject().put("id", value.getId()).put("messageId", value.getMessageId()).put("status", value.getStatus())
                .put("fileId", value.getFileId()).put("sizeBytes", value.getSizeBytes()).put("sha256", value.getSha256())
                .put("attempts", value.getAttemptCount()).put("errorCode", value.getErrorCode());
        }
        var tasks = state.putArray("tasks");
        for (var value : database.mapper(CollaborationTaskMapper.class).selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
                .eq(CollaborationTaskRow::getEnterpriseId, enterprise))) {
            tasks.addObject().put("id", value.getId()).put("code", value.getShortCode()).put("status", value.getStatus())
                .put("revision", value.getRevision()).put("inputRevision", value.getInputRevision()).put("owner", value.getOwnerParticipantId())
                .put("handoffs", value.getHandoffCount()).put("runs", value.getRunCount()).put("finalArtifact", value.getFinalArtifactId());
        }
        var participants = state.putArray("participants");
        for (var value : database.mapper(CollaborationParticipantMapper.class).selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
                .eq(CollaborationParticipantRow::getEnterpriseId, enterprise))) {
            participants.addObject().put("id", value.getId()).put("taskId", value.getTaskId()).put("role", value.getRoleName())
                .put("botId", value.getBotBindingId()).put("agentId", value.getAgentId()).put("versionId", value.getAgentVersionId())
                .put("projectId", value.getProjectId()).put("conversationId", value.getConversationId());
        }
        var works = state.putArray("works");
        for (var value : database.mapper(CollaborationWorkItemMapper.class).selectList(new LambdaQueryWrapper<CollaborationWorkItemRow>()
                .eq(CollaborationWorkItemRow::getEnterpriseId, enterprise))) {
            works.addObject().put("id", value.getId()).put("taskId", value.getTaskId()).put("participantId", value.getParticipantId())
                .put("status", value.getStatus()).put("inputRevision", value.getInputRevision()).put("activeRunId", value.getActiveRunId())
                .put("resultArtifactId", value.getResultArtifactId());
        }
        var links = state.putArray("runs");
        for (var value : database.mapper(CollaborationRunLinkMapper.class).selectList(new LambdaQueryWrapper<CollaborationRunLinkRow>()
                .eq(CollaborationRunLinkRow::getEnterpriseId, enterprise))) {
            var run = runs.find(enterprise, value.getRunId(), false).orElseThrow();
            links.addObject().put("id", value.getRunId()).put("taskId", value.getTaskId()).put("workId", value.getWorkId())
                .put("participantId", value.getParticipantId()).put("contextId", value.getContextId()).put("status", value.getLastStatus())
                .put("maxSteps", ExecutionLimits.from(run).maxSteps())
                .put("projectId", run.executionConfig().path("workspaceProjectId").asText());
        }
        var actions = state.putArray("actions");
        for (var value : database.mapper(CollaborationActionMapper.class).selectList(new LambdaQueryWrapper<CollaborationActionRow>()
                .eq(CollaborationActionRow::getEnterpriseId, enterprise).orderByAsc(CollaborationActionRow::getCreatedAt))) {
            actions.addObject().put("id", value.getId()).put("taskId", value.getTaskId()).put("type", value.getActionType())
                .put("sourceRunId", value.getSourceRunId()).put("sourceToolCallId", value.getSourceToolCallId());
        }
        var artifacts = state.putArray("artifacts");
        for (var value : database.mapper(CollaborationArtifactMapper.class).selectList(new LambdaQueryWrapper<CollaborationArtifactRow>()
                .eq(CollaborationArtifactRow::getEnterpriseId, enterprise))) {
            artifacts.addObject().put("id", value.getId()).put("taskId", value.getTaskId()).put("title", value.getTitle())
                .put("kind", value.getKind()).put("fileId", value.getFileId());
        }
        var outbounds = state.putArray("outbounds");
        for (var value : database.mapper(ChatOutboundMessageMapper.class).selectList(new LambdaQueryWrapper<ChatOutboundMessageRow>()
                .eq(ChatOutboundMessageRow::getEnterpriseId, enterprise).orderByAsc(ChatOutboundMessageRow::getCreatedAt))) {
            outbounds.addObject().put("id", value.getId()).put("taskId", value.getTaskId()).put("botId", value.getBotBindingId())
                .put("kind", value.getKind()).put("status", value.getStatus()).put("attempts", value.getAttemptCount())
                .put("errorCode", value.getErrorCode()).put("platformMessageId", value.getPlatformMessageId());
        }
        Files.writeString(directory.resolve("state.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(state));
    }

    boolean hasCompletedHandoff() {
        var completed = database.mapper(CollaborationTaskMapper.class).selectList(new LambdaQueryWrapper<CollaborationTaskRow>()
            .eq(CollaborationTaskRow::getEnterpriseId, enterprise).eq(CollaborationTaskRow::getStatus, "completed")
            .gt(CollaborationTaskRow::getHandoffCount, 0));
        return completed.stream().anyMatch(task -> database.mapper(ChatOutboundMessageMapper.class)
            .selectCount(new LambdaQueryWrapper<ChatOutboundMessageRow>().eq(ChatOutboundMessageRow::getEnterpriseId, enterprise)
                .eq(ChatOutboundMessageRow::getTaskId, task.getId()).eq(ChatOutboundMessageRow::getStatus, "sent")
                .eq(ChatOutboundMessageRow::getKind, "completed")) > 0);
    }
}
