package com.stonewu.agenteam.mapper.agent;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.toolkit.JoinWrappers;
import com.stonewu.agenteam.model.agent.entity.AgentHireApplicationQueryRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRequestRow;
import com.stonewu.agenteam.model.enterprise.entity.EnterpriseMemberRow;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.ResourceRow;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * AgentHireApplicationMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface AgentHireApplicationSqlMapper extends MPJBaseMapper<AgentHireRequestRow> {

    List<AgentHireApplicationQueryRow> listApplications(@Param("enterprise") String enterprise,
                                                        @Param("user") String user,
                                                        @Param("includeOwn") boolean includeOwn,
                                                        @Param("approval") ResourceQueryScope approval,
                                                        @Param("cursor") PagePosition cursor,
                                                        @Param("limit") int limit);

    default List<AgentHireApplicationQueryRow> findAgentHireRequest(String enterprise, String id, boolean lock) {
        if (lock) {
            return findAgentHireRequestLocked(enterprise, id);
        }
        var query = JoinWrappers.lambda(AgentHireRequestRow.class).selectAll(AgentHireRequestRow.class)
            .selectAs(EnterpriseMemberRow::getDisplayName, AgentHireApplicationQueryRow::getApplicantName)
            .selectAs(ResourceVersionRow::getName, AgentHireApplicationQueryRow::getAgentName)
            .selectAs(ResourceVersionRow::getConfigJson, AgentHireApplicationQueryRow::getAgentConfigJson)
            .innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, AgentHireRequestRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, AgentHireRequestRow::getUserId)).innerJoin(ResourceRow.class,
                on -> on.eq(ResourceRow::getEnterpriseId, AgentHireRequestRow::getEnterpriseId)
                    .eq(ResourceRow::getId, AgentHireRequestRow::getAgentId)).innerJoin(ResourceVersionRow.class,
                on -> on.eq(ResourceVersionRow::getEnterpriseId, ResourceRow::getEnterpriseId)
                    .eq(ResourceVersionRow::getId, ResourceRow::getPublishedVersionId))
            .eq(AgentHireRequestRow::getEnterpriseId, enterprise).eq(AgentHireRequestRow::getId, id);
        return selectJoinList(AgentHireApplicationQueryRow.class, query);
    }

    List<AgentHireApplicationQueryRow> findAgentHireRequestLocked(@Param("enterprise") String enterprise,
                                                                  @Param("id") String id);

    default List<AgentHireApplicationQueryRow> pendingAgentHireRequest(String enterprise, String user, String agent) {
        var criteria = JoinWrappers.lambda(AgentHireRequestRow.class).selectAll(AgentHireRequestRow.class)
            .selectAs(EnterpriseMemberRow::getDisplayName, AgentHireApplicationQueryRow::getApplicantName)
            .selectAs(ResourceVersionRow::getName, AgentHireApplicationQueryRow::getAgentName)
            .selectAs(ResourceVersionRow::getConfigJson, AgentHireApplicationQueryRow::getAgentConfigJson)
            .innerJoin(EnterpriseMemberRow.class,
                on -> on.eq(EnterpriseMemberRow::getEnterpriseId, AgentHireRequestRow::getEnterpriseId)
                    .eq(EnterpriseMemberRow::getUserId, AgentHireRequestRow::getUserId)).innerJoin(ResourceRow.class,
                on -> on.eq(ResourceRow::getEnterpriseId, AgentHireRequestRow::getEnterpriseId)
                    .eq(ResourceRow::getId, AgentHireRequestRow::getAgentId)).innerJoin(ResourceVersionRow.class,
                on -> on.eq(ResourceVersionRow::getEnterpriseId, ResourceRow::getEnterpriseId)
                    .eq(ResourceVersionRow::getId, ResourceRow::getPublishedVersionId))
            .eq(AgentHireRequestRow::getEnterpriseId, enterprise).eq(AgentHireRequestRow::getUserId, user)
            .eq(AgentHireRequestRow::getAgentId, agent).eq(AgentHireRequestRow::getStatus, "pending");
        return selectJoinList(AgentHireApplicationQueryRow.class, criteria);
    }

    default int createAgentHireRequest(String id, String enterprise, String user, String agent, String note,
                                       Timestamp expires, Timestamp now) {
        var databaseRow = new AgentHireRequestRow();
        databaseRow.setPendingMarker(1);
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setUserId(user);
        databaseRow.setAgentId(agent);
        databaseRow.setRequestNote(note);
        databaseRow.setExpiresAt((expires == null ? null : expires.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int finishAgentHireRequest(String status, String actor, String note, Timestamp now, String enterpriseId,
                                       String id, long revision) {
        return update(
            new LambdaUpdateWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getEnterpriseId, enterpriseId)
                .eq(AgentHireRequestRow::getId, id).eq(AgentHireRequestRow::getStatus, "pending")
                .eq(AgentHireRequestRow::getRevision, revision).set(AgentHireRequestRow::getStatus, status)
                .set(AgentHireRequestRow::getPendingMarker, null).set(AgentHireRequestRow::getDecidedBy, actor)
                .set(AgentHireRequestRow::getDecisionNote, note).set(AgentHireRequestRow::getDecidedAt, now)
                .setIncrBy(AgentHireRequestRow::getRevision, 1).set(AgentHireRequestRow::getUpdatedAt, now));
    }

    default int expireForAgentHireRequest(Timestamp now, String enterprise, String user, String agent) {
        return update(
            new LambdaUpdateWrapper<AgentHireRequestRow>().eq(AgentHireRequestRow::getEnterpriseId, enterprise)
                .eq(AgentHireRequestRow::getUserId, user).eq(AgentHireRequestRow::getAgentId, agent)
                .eq(AgentHireRequestRow::getStatus, "pending").le(AgentHireRequestRow::getExpiresAt, now)
                .set(AgentHireRequestRow::getStatus, "expired").set(AgentHireRequestRow::getPendingMarker, null)
                .setIncrBy(AgentHireRequestRow::getRevision, 1).set(AgentHireRequestRow::getUpdatedAt, now));
    }

    int expireAgentHireRequest(@Param("now") Timestamp now);
}
