package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatBotBindingRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.service.collaboration.CollaborationInputArtifacts;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/** 用户回复自己上传的文件时，只按平台原消息编号取得附件，不猜测最近文件的归属。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ChatReplyAttachmentService {
    private final ChatMessageMapper messages;
    private final ChatMessageResourceMapper resources;
    private final ChatIdentityService identities;
    private final ChatPayloadCipher cipher;
    private final CollaborationWorkItemMapper works;
    private final CollaborationInputArtifacts artifacts;
    private final Clock clock;

    public ChatReplyAttachmentService(ChatMessageMapper messages, ChatMessageResourceMapper resources,
            ChatIdentityService identities, ChatPayloadCipher cipher, CollaborationWorkItemMapper works,
            CollaborationInputArtifacts artifacts, Clock clock) {
        this.messages = messages;
        this.resources = resources;
        this.identities = identities;
        this.cipher = cipher;
        this.works = works;
        this.artifacts = artifacts;
        this.clock = clock;
    }

    public Reference prepare(AuthContext actor, ChatBotBindingRow entry, ChatMessageRow reply, String taskId) {
        if (reply.getReplyToPlatformId() == null) {
            return new Reference(null, List.of());
        }
        var original = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>()
            .eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId()).eq(ChatMessageRow::getConversationId, reply.getConversationId())
            .eq(ChatMessageRow::getPlatformMessageId, reply.getReplyToPlatformId()));
        if (original == null) {
            return new Reference(null, List.of());
        }
        var files = resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, actor.enterpriseId()).eq(ChatMessageResourceRow::getMessageId, original.getId()));
        if (files.isEmpty()) {
            return new Reference(null, List.of());
        }
        if (!"user".equals(original.getSenderKind()) || Set.of("failed", "awaiting_clarification").contains(original.getRoutingStatus())
            || original.getTaskId() != null && !original.getTaskId().equals(taskId)) {
            throw unavailable();
        }
        var sender = cipher.decrypt(actor.enterpriseId(), "message-senders", original.getId(), original.getSenderIdentitiesEncrypted()).path(entry.getId());
        var binding = identities.find(actor.enterpriseId(), entry.getConnectionId(), sender.path("subjectType").asText(),
            sender.path("subjectId").asText(), sender.path("unionId").asText(null))
            .filter(value -> actor.userId().equals(value.getUserId())).orElseThrow(ChatReplyAttachmentService::unavailable);
        identities.actor(actor.enterpriseId(), actor.userId(), binding.getId());
        // 未被明确引用的普通群文件不下载；本次通过身份核对后才允许原下载工作继续。
        changed(messages.update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageRow::getId, original.getId()).eq(ChatMessageRow::getRevision, original.getRevision())
            .set(ChatMessageRow::getSenderUserId, actor.userId()).set(ChatMessageRow::getOriginBindingId, binding.getId())
            .set(original.getTaskId() == null, ChatMessageRow::getRoutingStatus, "awaiting_reference")
            .set(ChatMessageRow::getUpdatedAt, clock.instant())));
        resources.update(new LambdaUpdateWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageResourceRow::getMessageId, original.getId()).eq(ChatMessageResourceRow::getStatus, "failed")
            .eq(ChatMessageResourceRow::getErrorCode, "CHANNEL_BINDING_REQUIRED").isNull(ChatMessageResourceRow::getFileId)
            .isNotNull(ChatMessageResourceRow::getFetchMaterialEncrypted).set(ChatMessageResourceRow::getStatus, "pending")
            .set(ChatMessageResourceRow::getErrorCode, null).set(ChatMessageResourceRow::getNextAttemptAt, clock.instant()));
        files = resources.selectList(new LambdaQueryWrapper<ChatMessageResourceRow>()
            .eq(ChatMessageResourceRow::getEnterpriseId, actor.enterpriseId()).eq(ChatMessageResourceRow::getMessageId, original.getId()));
        return new Reference(original.getId(), files);
    }

    public void attach(AuthContext actor, CollaborationTaskRow task, Reference reference) {
        if (reference.messageId() == null) {
            return;
        }
        var owner = works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>()
            .eq(CollaborationWorkItemRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationWorkItemRow::getTaskId, task.getId())
            .eq(CollaborationWorkItemRow::getId, task.getOwnerWorkId()));
        if (owner == null) {
            throw unavailable();
        }
        artifacts.register(actor, task, owner, reference.messageId());
        changed(messages.update(new LambdaUpdateWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageRow::getId, reference.messageId()).eq(ChatMessageRow::getConversationId, task.getConversationId())
            .eq(ChatMessageRow::getSenderUserId, actor.userId())
            .and(query -> query.isNull(ChatMessageRow::getTaskId).or().eq(ChatMessageRow::getTaskId, task.getId()))
            .set(ChatMessageRow::getTaskId, task.getId()).set(ChatMessageRow::getRoutingStatus, "routed")
            .set(ChatMessageRow::getUpdatedAt, clock.instant())));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.FORBIDDEN, "CHAT_ATTACHMENT_REFERENCE_DENIED", "只能引用当前会话中本人上传且未归属其他任务的附件。");
    }

    private void changed(int count) {
        if (count != 1) {
            throw unavailable();
        }
    }

    public record Reference(String messageId, List<ChatMessageResourceRow> resources) {
    }
}
