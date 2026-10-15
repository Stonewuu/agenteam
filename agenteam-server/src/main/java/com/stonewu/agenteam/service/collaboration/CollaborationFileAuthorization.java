package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageMapper;
import com.stonewu.agenteam.mapper.chat.ChatMessageResourceMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationRunLinkMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.chat.entity.ChatMessageResourceRow;
import com.stonewu.agenteam.model.chat.entity.ChatMessageRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationRunLinkRow;
import com.stonewu.agenteam.model.file.entity.FileRecord;
import com.stonewu.agenteam.service.chat.ChatIdentityService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 协作文件只能由原发起人读取，还需匹配已保存的岗位执行或平台消息。 */
@Service
public class CollaborationFileAuthorization {
    private final CollaborationRunLinkMapper links;
    private final ChatMessageResourceMapper resources;
    private final ChatMessageMapper messages;
    private final CollaborationAccessService access;
    private final ChatIdentityService identities;

    public CollaborationFileAuthorization(CollaborationRunLinkMapper links, ChatMessageResourceMapper resources,
            ChatMessageMapper messages, CollaborationAccessService access, ChatIdentityService identities) {
        this.links = links;
        this.resources = resources;
        this.messages = messages;
        this.access = access;
        this.identities = identities;
    }

    public void require(AuthContext actor, FileRecord file) {
        if (!actor.enterpriseId().equals(file.enterpriseId()) || !actor.userId().equals(file.ownerUserId()) || !file.purpose().equals("collaboration_artifact")) {
            throw unavailable();
        }
        if (file.runId() != null) {
            var link = links.selectOne(new LambdaQueryWrapper<CollaborationRunLinkRow>().eq(CollaborationRunLinkRow::getEnterpriseId, actor.enterpriseId())
                .eq(CollaborationRunLinkRow::getRunId, file.runId()));
            if (link == null) {
                throw unavailable();
            }
            var task = access.owned(actor, link.getTaskId());
            access.requireArtifactRole(task, access.participant(task, link.getParticipantId()));
            return;
        }
        var resource = resources.selectOne(new LambdaQueryWrapper<ChatMessageResourceRow>().eq(ChatMessageResourceRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageResourceRow::getFileId, file.id()));
        if (resource == null) {
            throw unavailable();
        }
        var message = messages.selectOne(new LambdaQueryWrapper<ChatMessageRow>().eq(ChatMessageRow::getEnterpriseId, actor.enterpriseId())
            .eq(ChatMessageRow::getId, resource.getMessageId()).eq(ChatMessageRow::getSenderUserId, actor.userId()));
        if (message == null || message.getOriginBindingId() == null) {
            throw unavailable();
        }
        identities.actor(actor.enterpriseId(), actor.userId(), message.getOriginBindingId());
        if (message.getTaskId() != null) {
            access.owned(actor, message.getTaskId());
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "ARTIFACT_ACCESS_DENIED", "文件不属于当前用户可读取的协作任务。");
    }
}
