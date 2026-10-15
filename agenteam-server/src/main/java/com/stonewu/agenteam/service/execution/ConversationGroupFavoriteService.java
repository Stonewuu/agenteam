package com.stonewu.agenteam.service.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationParticipantMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationParticipantRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.ConversationRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

/** 调用方已锁定企业和本人会话；整项任务收藏复用原岗位收藏，历史收藏不会丢失。 */
@Service
public class ConversationGroupFavoriteService {
    private final CollaborationParticipantMapper participants;
    private final CollaborationTaskMapper tasks;
    private final ConversationSqlMapper conversations;
    private final Clock clock;

    public ConversationGroupFavoriteService(CollaborationParticipantMapper participants, CollaborationTaskMapper tasks,
        ConversationSqlMapper conversations, Clock clock) {
        this.participants = participants;
        this.tasks = tasks;
        this.conversations = conversations;
        this.clock = clock;
    }

    public boolean update(AuthContext actor, ConversationRecord conversation, Boolean favorite) {
        if (favorite == null || !"collaboration".equals(conversation.sourceType())) {
            return false;
        }
        var role = participants.selectOne(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationParticipantRow::getConversationId, conversation.id()));
        if (role == null) {
            return false;
        }
        if (!tasks.exists(new LambdaQueryWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, actor.enterpriseId())
            .eq(CollaborationTaskRow::getInitiatorUserId, actor.userId()).eq(CollaborationTaskRow::getId, role.getTaskId()))) {
            throw ResourceAuthorizationService.unavailable();
        }
        var ids = participants.selectList(new LambdaQueryWrapper<CollaborationParticipantRow>()
            .eq(CollaborationParticipantRow::getEnterpriseId, actor.enterpriseId()).eq(CollaborationParticipantRow::getTaskId, role.getTaskId()))
            .stream().map(CollaborationParticipantRow::getConversationId).filter(Objects::nonNull).toList();
        var owned = conversations.selectList(new LambdaQueryWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId()).eq(AgentConversationRow::getUserId, actor.userId())
            .eq(AgentConversationRow::getSourceType, "collaboration").ne(AgentConversationRow::getStatus, "deleted")
            .in(AgentConversationRow::getId, ids).orderByAsc(AgentConversationRow::getId));
        for (var row : owned) {
            if (conversations.update(new LambdaUpdateWrapper<AgentConversationRow>()
                .eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId()).eq(AgentConversationRow::getUserId, actor.userId())
                .eq(AgentConversationRow::getId, row.getId()).eq(AgentConversationRow::getRevision, row.getRevision())
                .eq(AgentConversationRow::getSourceType, "collaboration").ne(AgentConversationRow::getStatus, "deleted")
                .set(AgentConversationRow::getFavorite, favorite ? 1 : 0).setIncrBy(AgentConversationRow::getRevision, 1)
                .set(AgentConversationRow::getUpdatedAt, clock.instant())) != 1) {
                throw ApiException.versionConflict(row.getRevision());
            }
        }
        return true;
    }
}
