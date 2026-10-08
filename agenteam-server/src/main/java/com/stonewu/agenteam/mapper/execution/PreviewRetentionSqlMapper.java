package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.PreviewRetentionQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * id 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface PreviewRetentionSqlMapper extends MPJBaseMapper<AgentConversationRow> {
    default List<PreviewRetentionQueryRow> expiredAgentConversation(Timestamp before) {
        var criteria = new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getEnterpriseId,
                AgentConversationRow::getUserId, AgentConversationRow::getId).orderByAsc(AgentConversationRow::getCreatedAt)
            .orderByAsc(AgentConversationRow::getId).eq(AgentConversationRow::getMode, "preview")
            .le(AgentConversationRow::getCreatedAt, before).isNull(AgentConversationRow::getActiveRunId);
        long pageSize = 50;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<AgentConversationRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new PreviewRetentionQueryRow();
                if (storedRow.getEnterpriseId() != null) {
                    mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
                }
                if (storedRow.getUserId() != null) {
                    mappedRow.setUserId(storedRow.getUserId());
                }
                if (storedRow.getId() != null) {
                    mappedRow.setId(storedRow.getId());
                }
                return mappedRow;
            }).toList();
    }


    int removeMessageFeedback(@Param("enterprise") String enterprise, @Param("conversation") String conversation);


    default int removeAgentConversation(String enterprise, String conversation) {
        return delete(
            new LambdaQueryWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
                .eq(AgentConversationRow::getId, conversation));
    }
}
