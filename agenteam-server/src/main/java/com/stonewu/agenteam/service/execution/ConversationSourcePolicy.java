package com.stonewu.agenteam.service.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.execution.ConversationSqlMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 岗位会话允许查看，但不能从普通对话入口替换固定项目、模型或审批策略。 */
@Service
public class ConversationSourcePolicy {
    private final ConversationSqlMapper conversations;

    public ConversationSourcePolicy(ConversationSqlMapper conversations) {
        this.conversations = conversations;
    }

    public void requireWeb(AuthContext actor, String id) {
        var row = conversations.selectOne(new LambdaQueryWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, actor.enterpriseId())
            .eq(AgentConversationRow::getId, id).eq(AgentConversationRow::getUserId, actor.userId()));
        if (row != null && "collaboration".equals(row.getSourceType())) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLABORATION_INPUT_REQUIRED", "请在协作任务详情中管理任务，岗位会话由任务统一安排。");
        }
    }
}
