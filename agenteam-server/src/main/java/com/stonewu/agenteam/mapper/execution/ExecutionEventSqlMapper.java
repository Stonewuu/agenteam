package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentEventRow;
import com.stonewu.agenteam.model.execution.entity.ExecutionEventQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * 事件按数据库行保存，行的顺序编号表示其中最后一个原事件。
 */
@Mapper
public interface ExecutionEventSqlMapper extends MPJBaseMapper<AgentEventRow> {
    List<Long> appendAgentEvent(@Param("id") String id);

    AgentEventRow lastForConversation(@Param("enterprise") String enterprise,
                                      @Param("conversation") String conversation);

    List<AgentEventRow> readWindow(@Param("enterprise") String enterprise, @Param("conversation") String conversation,
                                   @Param("after") long after, @Param("boundary") long boundary,
                                   @Param("limit") int limit);

    default List<AgentEventRow> afterAgentEvent(String enterprise, String conversation, long after, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        long boundary = after > Long.MAX_VALUE - limit ? Long.MAX_VALUE : after + limit;
        return readWindow(enterprise, conversation, after, boundary, limit);
    }

    default List<AgentEventRow> unpublishedAgentEvent(String enterprise, String conversation, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return selectPage(new Page<AgentEventRow>(1, limit, false), scope(enterprise, conversation)
            .isNull(AgentEventRow::getPublishedAt)
            .orderByAsc(AgentEventRow::getConversationSequence)).getRecords();
    }

    default List<AgentEventRow> legacyDeltas(int limit) {
        return selectPage(new Page<AgentEventRow>(1, limit, false), new LambdaQueryWrapper<AgentEventRow>()
            .eq(AgentEventRow::getStorageVersion, 1).eq(AgentEventRow::getEventType, "message.delta")
            .orderByAsc(AgentEventRow::getCreatedAt)).getRecords();
    }

    default int replaceBatch(AgentEventRow previous, AgentEventRow next) {
        return update(new LambdaUpdateWrapper<AgentEventRow>()
            .eq(AgentEventRow::getEnterpriseId, previous.getEnterpriseId())
            .eq(AgentEventRow::getConversationId, previous.getConversationId())
            .eq(AgentEventRow::getEventId, previous.getEventId())
            .eq(AgentEventRow::getConversationSequence, previous.getConversationSequence())
            .eq(AgentEventRow::getPayloadHash, previous.getPayloadHash())
            .set(AgentEventRow::getSequenceNo, next.getSequenceNo())
            .set(AgentEventRow::getConversationSequence, next.getConversationSequence())
            .set(AgentEventRow::getStorageVersion, next.getStorageVersion())
            .set(AgentEventRow::getPayloadJson, next.getPayloadJson())
            .set(AgentEventRow::getPayloadHash, next.getPayloadHash())
            .set(AgentEventRow::getFinishedAt, next.getFinishedAt())
            .set(AgentEventRow::getPublishedAt, null).set(AgentEventRow::getStreamId, null));
    }

    default int deleteMergedRows(String enterprise, String conversation, List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return delete(scope(enterprise, conversation).in(AgentEventRow::getEventId, ids));
    }

    int publishedThroughAgentEvent(@Param("now") Timestamp now, @Param("enterprise") String enterprise,
                                   @Param("conversation") String conversation, @Param("sequence") long sequence);

    int removeExpiredAgentEvent(@Param("boundary") Timestamp boundary);

    List<ExecutionEventQueryRow> pendingConversationsAgentEvent(@Param("limit") int limit);

    default int removeAgentEvent(String enterpriseId, String id) {
        return delete(new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getEnterpriseId, enterpriseId)
            .eq(AgentEventRow::getRunId, id));
    }

    private static LambdaQueryWrapper<AgentEventRow> scope(String enterprise, String conversation) {
        return new LambdaQueryWrapper<AgentEventRow>().eq(AgentEventRow::getEnterpriseId, enterprise)
            .eq(AgentEventRow::getConversationId, conversation);
    }
}
