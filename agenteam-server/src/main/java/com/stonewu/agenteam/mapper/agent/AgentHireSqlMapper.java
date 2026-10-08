package com.stonewu.agenteam.mapper.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.agent.entity.AgentHireQueryRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/**
 * AgentHireMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface AgentHireSqlMapper extends MPJBaseMapper<AgentHireRow> {

    default List<AgentHireQueryRow> findAgentHire(String enterprise, String user, String id, boolean lock) {
        if (lock) {
            return findAgentHireLocked(enterprise, user, id, lock);
        }
        var criteria = new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise)
            .eq(AgentHireRow::getUserId, user).eq(AgentHireRow::getId, id);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new AgentHireQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getAgentId() != null) {
                mappedRow.setAgentId(storedRow.getAgentId());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getHiredAt() != null) {
                mappedRow.setHiredAt((storedRow.getHiredAt() == null ? null : Timestamp.from(storedRow.getHiredAt())));
            }
            if (storedRow.getLastUsedAt() != null) {
                mappedRow.setLastUsedAt(
                    (storedRow.getLastUsedAt() == null ? null : Timestamp.from(storedRow.getLastUsedAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<AgentHireQueryRow> findAgentHireLocked(@Param("enterprise") String enterprise, @Param("user") String user,
                                                @Param("id") String id, @Param("lock") boolean lock);

    default List<AgentHireQueryRow> forAgentAgentHire(String enterprise, String user, String agent, boolean lock) {
        if (lock) {
            return forAgentAgentHireLocked(enterprise, user, agent, lock);
        }
        var criteria = new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise)
            .eq(AgentHireRow::getUserId, user).eq(AgentHireRow::getAgentId, agent);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new AgentHireQueryRow();
            if (storedRow.getId() != null) {
                mappedRow.setId(storedRow.getId());
            }
            if (storedRow.getEnterpriseId() != null) {
                mappedRow.setEnterpriseId(storedRow.getEnterpriseId());
            }
            if (storedRow.getUserId() != null) {
                mappedRow.setUserId(storedRow.getUserId());
            }
            if (storedRow.getAgentId() != null) {
                mappedRow.setAgentId(storedRow.getAgentId());
            }
            if (storedRow.getStatus() != null) {
                mappedRow.setStatus(storedRow.getStatus());
            }
            if (storedRow.getHiredAt() != null) {
                mappedRow.setHiredAt((storedRow.getHiredAt() == null ? null : Timestamp.from(storedRow.getHiredAt())));
            }
            if (storedRow.getLastUsedAt() != null) {
                mappedRow.setLastUsedAt(
                    (storedRow.getLastUsedAt() == null ? null : Timestamp.from(storedRow.getLastUsedAt())));
            }
            if (storedRow.getRevision() != null) {
                mappedRow.setRevision(storedRow.getRevision());
            }
            if (storedRow.getCreatedAt() != null) {
                mappedRow.setCreatedAt(
                    (storedRow.getCreatedAt() == null ? null : Timestamp.from(storedRow.getCreatedAt())));
            }
            if (storedRow.getUpdatedAt() != null) {
                mappedRow.setUpdatedAt(
                    (storedRow.getUpdatedAt() == null ? null : Timestamp.from(storedRow.getUpdatedAt())));
            }
            return mappedRow;
        }).toList();
    }

    List<AgentHireQueryRow> forAgentAgentHireLocked(@Param("enterprise") String enterprise, @Param("user") String user,
                                                    @Param("agent") String agent, @Param("lock") boolean lock);

    default int establishAgentHire(String id, String enterprise, String user, String agent, Timestamp now) {
        var databaseRow = new AgentHireRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterprise);
        databaseRow.setUserId(user);
        databaseRow.setAgentId(agent);
        databaseRow.setHiredAt((now == null ? null : now.toInstant()));
        databaseRow.setCreatedAt((now == null ? null : now.toInstant()));
        databaseRow.setUpdatedAt((now == null ? null : now.toInstant()));
        return insert(databaseRow);
    }

    default int changeStatusAgentHire(String status, Timestamp now, String enterpriseId, String userId, String id,
                                      long revision) {
        return update(new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterpriseId)
            .eq(AgentHireRow::getUserId, userId).eq(AgentHireRow::getId, id).eq(AgentHireRow::getRevision, revision)
            .set(AgentHireRow::getStatus, status).set("active".equals(status), AgentHireRow::getHiredAt, now)
            .set(AgentHireRow::getPausedAt, "paused".equals(status) ? now : null)
            .set(AgentHireRow::getTerminatedAt, "terminated".equals(status) ? now : null)
            .setIncrBy(AgentHireRow::getRevision, 1).set(AgentHireRow::getUpdatedAt, now));
    }

    default int pauseForResourceAgentHire(Timestamp now, String enterprise, String agent) {
        return update(new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise)
            .eq(AgentHireRow::getAgentId, agent).eq(AgentHireRow::getStatus, "active")
            .set(AgentHireRow::getStatus, "paused").set(AgentHireRow::getPausedAt, now)
            .setIncrBy(AgentHireRow::getRevision, 1).set(AgentHireRow::getUpdatedAt, now));
    }
}
