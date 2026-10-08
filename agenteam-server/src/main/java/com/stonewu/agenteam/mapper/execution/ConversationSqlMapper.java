package com.stonewu.agenteam.mapper.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.execution.entity.AgentConversationRow;
import com.stonewu.agenteam.model.execution.entity.ConversationQueryRow;
import com.stonewu.agenteam.model.execution.entity.ModelSelection;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * ConversationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface ConversationSqlMapper extends MPJBaseMapper<AgentConversationRow> {
    default String eventDeliveryMode(String enterprise, String id) {
        var row = selectOne(
            new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getEventDeliveryMode)
                .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, id));
        return row == null ? null : row.getEventDeliveryMode();
    }

    default int enableLiveEvents(String enterprise, String user, String id) {
        return update(new LambdaUpdateWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getUserId, user)
            .eq(AgentConversationRow::getId, id).eq(AgentConversationRow::getStatus, "active")
            .eq(AgentConversationRow::getEventDeliveryMode, "database").isNull(AgentConversationRow::getActiveRunId)
            .set(AgentConversationRow::getEventDeliveryMode, "redis"));
    }

    default int selectProject(String enterprise, String user, String id, long revision, String projectId,
                              boolean initial, Instant now) {
        return update(
            new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
                .eq(AgentConversationRow::getUserId, user).eq(AgentConversationRow::getId, id)
                .eq(AgentConversationRow::getRevision, revision)
                .eq(AgentConversationRow::getMode, "normal").ne(AgentConversationRow::getStatus, "deleted")
                .isNull(initial, AgentConversationRow::getProjectId)
                .isNull(!initial, AgentConversationRow::getActiveRunId)
                .set(AgentConversationRow::getProjectId, projectId).setIncrBy(AgentConversationRow::getRevision, 1)
                .set(AgentConversationRow::getUpdatedAt, now));
    }

    default int selectModel(String enterprise, String user, String id, long revision, ModelSelection selection,
                            Instant now) {
        return update(new LambdaUpdateWrapper<AgentConversationRow>()
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getUserId, user)
            .eq(AgentConversationRow::getId, id).eq(AgentConversationRow::getRevision, revision)
            .eq(AgentConversationRow::getStatus, "active").isNull(AgentConversationRow::getActiveRunId)
            .set(AgentConversationRow::getModelProfileId, selection.modelProfileId())
            .set(AgentConversationRow::getReasoningEffort, selection.reasoningEffort())
            .setIncrBy(AgentConversationRow::getRevision, 1).set(AgentConversationRow::getUpdatedAt, now));
    }

    List<ConversationQueryRow> findConversation(@Param("enterprise") String enterprise, @Param("user") String user,
                                                @Param("id") String id, @Param("lock") boolean lock);

    List<ConversationQueryRow> listConversations(@Param("enterprise") String enterprise, @Param("user") String user,
                                                 @Param("status") String status, @Param("favorite") Boolean favorite,
                                                 @Param("agentId") String agentId, @Param("query") String query,
                                                 @Param("after") PagePosition after, @Param("limit") int limit,
                                                 @Param("oldestDeleted") Instant oldestDeleted);

    default int createAgentConversation(String id, String enterprise, String user, String agent, String version,
                                        String hire, String title, String mode, String approvalPolicy, Timestamp now) {
        var databaseRow = new AgentConversationRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setUserId(user);
        databaseRow.setAgentId(agent);
        databaseRow.setAgentVersionId(version);
        databaseRow.setHireId(hire);
        databaseRow.setTitle(title);
        databaseRow.setMode(mode);
        databaseRow.setApprovalPolicy(approvalPolicy);
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int createWorkflowPreviewAgentConversation(String id, String enterprise, String user, String workflow,
                                                       String title, Timestamp now) {
        var databaseRow = new AgentConversationRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setUserId(user);
        databaseRow.setPreviewResourceId(workflow);
        databaseRow.setTitle(title);
        databaseRow.setMode("preview");
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default List<ConversationQueryRow> streamPositionAgentConversation(String enterprise, String user, String id,
                                                                       Timestamp oldestPreview) {
        var criteria = new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getStatus,
                AgentConversationRow::getActiveRunId, AgentConversationRow::getLastSequence)
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getUserId, user)
            .eq(AgentConversationRow::getId, id).and(group -> group.ne(AgentConversationRow::getMode, "preview")
                .or(other -> other.gt(AgentConversationRow::getCreatedAt, oldestPreview)));
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new ConversationQueryRow();
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getActiveRunId() != null) {
                mappedRow.setActiveRunId(storedRow.getActiveRunId());
            }
            if (storedRow.getLastSequence() != null) {
                mappedRow.setLastSequence(storedRow.getLastSequence());
            }
            return mappedRow;
        }).toList();
    }

    default List<Long> lastSequenceAgentConversation(String enterprise, String id) {
        var criteria = new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getLastSequence)
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> storedRow.getLastSequence()).toList();
    }

    default int activateAgentConversation(String run, Timestamp now, String enterprise, String id) {
        return update(
            new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
                .eq(AgentConversationRow::getId, id).isNull(AgentConversationRow::getActiveRunId)
                .eq(AgentConversationRow::getStatus, "active").set(AgentConversationRow::getActiveRunId, run)
                .set(AgentConversationRow::getUpdatedAt, now).setIncrBy(AgentConversationRow::getRevision, 1));
    }

    default int finishAgentConversation(Timestamp now, String enterprise, String conversation, String run) {
        return update(
            new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
                .eq(AgentConversationRow::getId, conversation).eq(AgentConversationRow::getActiveRunId, run)
                .set(AgentConversationRow::getActiveRunId, null).set(AgentConversationRow::getUpdatedAt, now)
                .setIncrBy(AgentConversationRow::getRevision, 1));
    }

    default int advanceSequenceAgentConversation(Timestamp now, String enterprise, String conversation) {
        return update(
            new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterprise)
                .eq(AgentConversationRow::getId, conversation).setIncrBy(AgentConversationRow::getLastSequence, 1)
                .set(AgentConversationRow::getUpdatedAt, now));
    }

    default List<Long> readConversationSequence(String enterprise, String conversation) {
        var criteria = new LambdaQueryWrapper<AgentConversationRow>().select(AgentConversationRow::getLastSequence)
            .eq(AgentConversationRow::getEnterpriseId, enterprise).eq(AgentConversationRow::getId, conversation);
        return selectList(criteria).stream().map(storedRow -> storedRow.getLastSequence()).toList();
    }

    default int updateAgentConversation(String title, Boolean favorite, String status, String approvalPolicy,
                                        Timestamp now, String enterpriseId, String userId, String id, long revision) {
        return update(
            new LambdaUpdateWrapper<AgentConversationRow>().eq(AgentConversationRow::getEnterpriseId, enterpriseId)
                .eq(AgentConversationRow::getUserId, userId).eq(AgentConversationRow::getId, id)
                .eq(AgentConversationRow::getRevision, revision)
                .isNull(approvalPolicy != null, AgentConversationRow::getActiveRunId)
                .set(approvalPolicy != null, AgentConversationRow::getApprovalPolicy, approvalPolicy)
                .set(title != null, AgentConversationRow::getTitle, title)
                .set(title != null, AgentConversationRow::getTitleCustomized, 1)
                .set(favorite != null, AgentConversationRow::getFavorite, favorite)
                .set(AgentConversationRow::getStatus, status)
                .set(AgentConversationRow::getDeletedAt, "deleted".equals(status) ? now : null)
                .setIncrBy(AgentConversationRow::getRevision, 1).set(AgentConversationRow::getUpdatedAt, now));
    }
}
