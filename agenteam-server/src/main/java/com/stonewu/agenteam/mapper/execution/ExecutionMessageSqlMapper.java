package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.AgentMessageRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionMessageQueryRow;
import com.stonewu.agenteam.model.execution.entity.MessageFeedbackRow;
import com.stonewu.agenteam.model.memory.entity.MemorySourceQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * ExecutionMessageMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ExecutionMessageSqlMapper extends MPJBaseMapper<AgentMessageRow> {
    List<ExecutionMessageQueryRow> historyMessages(@Param("enterprise") String enterprise,
                                                   @Param("conversation") String conversation,
                                                   @Param("beforeTime") Instant beforeTime,
                                                   @Param("beforeRole") int beforeRole,
                                                   @Param("beforeId") String beforeId, @Param("limit") int limit);

    default int createAgentMessage(String id, String enterprise, String conversation, String role, String content,
                                   String status, int attemptNo, String blocksJson, String contextJson, Timestamp now) {
        var databaseRow = new AgentMessageRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setConversationId(conversation);
        databaseRow.setRole(role);
        databaseRow.setContent(content);
        databaseRow.setStatus(status);
        databaseRow.setAttemptNo(attemptNo);
        databaseRow.setBlocksJson(blocksJson);
        databaseRow.setContextJson(contextJson);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int associateAgentMessage(String run, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<AgentMessageRow>().eq(AgentMessageRow::getEnterpriseId, enterprise)
            .eq(AgentMessageRow::getId, id).isNull(AgentMessageRow::getRunId).set(AgentMessageRow::getRunId, run));
    }

    default List<ExecutionMessageQueryRow> findAgentMessage(String enterprise, String conversation, String id) {
        var criteria = JoinWrappers.lambda(AgentMessageRow.class).selectAll(AgentMessageRow.class)
            .selectAs(MessageFeedbackRow::getValue, ExecutionMessageQueryRow::getFeedback)
            .innerJoin(AgentConversationRow.class,
                on -> on.eq(AgentConversationRow::getEnterpriseId, AgentMessageRow::getEnterpriseId)
                    .eq(AgentConversationRow::getId, AgentMessageRow::getConversationId))
            .leftJoin(MessageFeedbackRow.class,
                on -> on.eq(MessageFeedbackRow::getEnterpriseId, AgentMessageRow::getEnterpriseId)
                    .eq(MessageFeedbackRow::getMessageId, AgentMessageRow::getId)
                    .eq(MessageFeedbackRow::getUserId, AgentConversationRow::getUserId))
            .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentMessageRow::getConversationId, conversation)
            .eq(AgentMessageRow::getId, id);
        return selectJoinList(ExecutionMessageQueryRow.class, criteria);
    }


    default List<String> inputAgentMessage(String enterprise, String id) {
        var criteria = new LambdaQueryWrapper<AgentMessageRow>().select(AgentMessageRow::getContextJson)
            .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentMessageRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> storedRow.getContextJson()).toList();
    }

    default int saveAgentMessage(String content, String blocksJson, String status, long sequence, Timestamp now,
                                 String enterprise, String id) {
        return update(new LambdaUpdateWrapper<AgentMessageRow>().eq(AgentMessageRow::getEnterpriseId, enterprise)
            .eq(AgentMessageRow::getId, id).set(AgentMessageRow::getContent, content)
            .set(AgentMessageRow::getBlocksJson, blocksJson).set(AgentMessageRow::getStatus, status)
            .set(AgentMessageRow::getLastSequence, sequence).set(AgentMessageRow::getUpdatedAt, now));
    }

    default int statusAgentMessage(String status, long sequence, Timestamp now, String enterprise, String id) {
        return update(new LambdaUpdateWrapper<AgentMessageRow>().eq(AgentMessageRow::getEnterpriseId, enterprise)
            .eq(AgentMessageRow::getId, id).set(AgentMessageRow::getStatus, status)
            .set(AgentMessageRow::getLastSequence, sequence).set(AgentMessageRow::getUpdatedAt, now));
    }


    int feedbackMessageFeedback2(@Param("enterprise") String enterprise, @Param("user") String user,
                                 @Param("message") String message, @Param("value") String value,
                                 @Param("comment") String comment, @Param("now") Timestamp now);

    default int removeAgentMessage(String enterprise, String conversation) {
        return update(new LambdaUpdateWrapper<AgentMessageRow>().eq(AgentMessageRow::getEnterpriseId, enterprise)
            .eq(AgentMessageRow::getConversationId, conversation).set(AgentMessageRow::getRunId, null));
    }

    default int deleteConversationMessages(String enterprise, String conversation) {
        return delete(new LambdaQueryWrapper<AgentMessageRow>().eq(AgentMessageRow::getEnterpriseId, enterprise)
            .eq(AgentMessageRow::getConversationId, conversation));
    }

    default List<MemorySourceQueryRow> findMemorySourceMessage(String enterprise, String user, String agent,
                                                               String message) {
        var criteria = JoinWrappers.lambda(AgentMessageRow.class).select(AgentMessageRow::getConversationId)
            .select(AgentMessageRow::getRunId).innerJoin(AgentConversationRow.class,
                on -> on.eq(AgentConversationRow::getEnterpriseId, AgentMessageRow::getEnterpriseId)
                    .eq(AgentConversationRow::getId, AgentMessageRow::getConversationId))
            .eq(AgentMessageRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getUserId, user)
            .eq(AgentConversationRow::getAgentId, agent).eq(AgentMessageRow::getId, message)
            .isNotNull(AgentMessageRow::getRunId);
        return selectJoinList(MemorySourceQueryRow.class, criteria);
    }
}
