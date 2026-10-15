package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationArtifactMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.execution.ExecutionMessageMapper;
import com.stonewu.agenteam.mapper.execution.RunMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationArtifactRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.request.CollaborationResultResendRequest;
import com.stonewu.agenteam.service.chat.ChatOutboundService;
import com.stonewu.agenteam.service.chat.ChatPayloadCipher;
import com.stonewu.agenteam.service.chat.ChatPublicLinks;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 重发固定成果只创建发送记录，原执行与成果版本保持不变。 */
@Service
public class CollaborationResultResendService {
    private final CollaborationAccessService access;
    private final CollaborationArtifactMapper artifactRows;
    private final CollaborationArtifactService artifacts;
    private final EnterpriseMapper enterprises;
    private final RunMapper runs;
    private final ExecutionMessageMapper messages;
    private final ChatPayloadCipher cipher;
    private final ChatOutboundService outbound;
    private final ChatPublicLinks links;

    public CollaborationResultResendService(CollaborationAccessService access, CollaborationArtifactMapper artifactRows,
            CollaborationArtifactService artifacts, EnterpriseMapper enterprises, RunMapper runs,
            ExecutionMessageMapper messages, ChatPayloadCipher cipher, ChatOutboundService outbound, ChatPublicLinks links) {
        this.access = access;
        this.artifactRows = artifactRows;
        this.artifacts = artifacts;
        this.enterprises = enterprises;
        this.runs = runs;
        this.messages = messages;
        this.cipher = cipher;
        this.outbound = outbound;
        this.links = links;
    }

    @Transactional
    public Map<String, Object> send(AuthContext actor, String id, CollaborationResultResendRequest input) {
        enterprises.lockEnterprise(actor.enterpriseId()).orElseThrow();
        var task = access.owned(actor, id);
        access.actor(task);
        if (!task.getRevision().equals(input.expectedRevision()) || input.expectedReplyModeRevision() == null
                || input.expectedReplyModeRevision() != CollaborationReplyModeService.revision(task)) {
            throw ApiException.versionConflict(task.getRevision());
        }
        if (!"completed".equals(task.getStatus()) || task.getFinalArtifactId() == null) {
            throw ApiException.invalidField("taskId", "任务完成后才能发送已有结果。");
        }
        var result = artifactRows.selectOne(new LambdaQueryWrapper<CollaborationArtifactRow>()
            .eq(CollaborationArtifactRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationArtifactRow::getTaskId, id)
            .eq(CollaborationArtifactRow::getId, task.getFinalArtifactId()));
        if (result == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ARTIFACT_ACCESS_DENIED", "当前成果已无法读取。");
        }
        artifacts.require(task, result.getId(), result.getArtifactVersion());
        var owner = access.participant(task, task.getOwnerParticipantId());
        access.requireRole(task, owner);
        var content = result.getContentEncrypted() == null ? null
            : cipher.decrypt(actor.enterpriseId(), "artifact-content", result.getId(), result.getContentEncrypted());
        List<CollaborationArtifactRow> selected = content != null && content.has("artifactRefs")
            ? artifacts.references(task, content.path("artifactRefs")) : List.of();
        String text = answer(task, result);
        if (text.codePointCount(0, text.length()) > 32000) {
            text = selected.stream().anyMatch(file -> file.getFileId() != null) ? "完整回复较长，请查看成果附件。"
                : "完整回复较长，请打开任务查看。\n" + links.task(actor.enterpriseId(), id);
        }
        String key = "resend:" + actor.userId() + ":" + input.requestId();
        var message = outbound.text(actor.enterpriseId(), task.getConversationId(), owner.getBotBindingId(), id, null, key, "completed", text, null);
        if ("cancelled".equals(message.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAT_SEND_FORBIDDEN", "原会话暂时无法接收消息，请检查机器人是否仍在会话中。");
        }
        var sent = new HashSet<String>();
        for (var file : selected) {
            if (file.getFileId() != null && sent.add(file.getFileId())) {
                outbound.file(actor.enterpriseId(), task.getConversationId(), owner.getBotBindingId(), id, null, key + ":file:" + file.getId(), file);
            }
        }
        return Map.of("outboundId", message.getId(), "status", message.getStatus());
    }

    private String answer(CollaborationTaskRow task, CollaborationArtifactRow result) {
        if (result.getProducerRunId() != null) {
            var run = runs.find(task.getEnterpriseId(), result.getProducerRunId(), false).orElse(null);
            if (run != null && "completed".equals(run.status())) {
                var message = messages.find(task.getEnterpriseId(), run.conversationId(), run.outputMessageId()).orElse(null);
                if (message != null && message.content() != null && !message.content().isBlank()) {
                    return message.content();
                }
            }
        }
        return cipher.decrypt(task.getEnterpriseId(), "artifact-summary", result.getId(), result.getSummaryEncrypted()).path("text").asText();
    }
}
