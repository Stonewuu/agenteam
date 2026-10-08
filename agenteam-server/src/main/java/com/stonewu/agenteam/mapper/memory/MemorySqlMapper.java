package com.stonewu.agenteam.mapper.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.memory.entity.AgentMemoryRow;
import com.stonewu.agenteam.model.memory.entity.MemoryQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * MemoryMapper 对应的数据库语句，参数绑定和查询结果均有明确类型。
 */
@Mapper
public interface MemorySqlMapper extends MPJBaseMapper<AgentMemoryRow> {
    default List<MemoryQueryRow> listMemories(String enterprise, String user, String agent, Instant now,
                                              PagePosition after, int limit) {
        var criteria = new LambdaQueryWrapper<AgentMemoryRow>().orderByDesc(AgentMemoryRow::getUpdatedAt)
            .orderByDesc(AgentMemoryRow::getId).eq(AgentMemoryRow::getEnterpriseId, enterprise)
            .eq(AgentMemoryRow::getUserId, user).eq(AgentMemoryRow::getAgentId, agent)
            .gt(AgentMemoryRow::getExpiresAt, now);
        if (after != null) {
            criteria.and(group -> group.lt(AgentMemoryRow::getUpdatedAt, after.time())
                .or(other -> other.eq(AgentMemoryRow::getUpdatedAt, after.time())
                    .lt(AgentMemoryRow::getId, after.id())));
        }
        long pageSize = limit;
        if (pageSize == 0) {
            return List.of();
        }
        return selectPage(new Page<AgentMemoryRow>(1, pageSize, false), criteria).getRecords().stream()
            .map(storedRow -> {
                var mappedRow = new MemoryQueryRow();
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
                if (storedRow.getMemoryKey() != null) {
                    mappedRow.setMemoryKey(storedRow.getMemoryKey());
                }
                if (storedRow.getContent() != null) {
                    mappedRow.setContent(storedRow.getContent());
                }
                if (storedRow.getSourceMessageId() != null) {
                    mappedRow.setSourceMessageId(storedRow.getSourceMessageId());
                }
                if (storedRow.getExpiresAt() != null) {
                    mappedRow.setExpiresAt(
                        (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
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

    List<MemoryQueryRow> listAgents(@Param("enterprise") String enterprise, @Param("user") String user,
                                    @Param("now") Instant now, @Param("query") String query,
                                    @Param("after") PagePosition after, @Param("limit") int limit);

    default List<MemoryQueryRow> findAgentMemory(String enterprise, String user, String agent, String id, Timestamp now,
                                                 boolean lock) {
        if (lock) {
            return findAgentMemoryLocked(enterprise, user, agent, id, now, lock);
        }
        var criteria = new LambdaQueryWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterprise)
            .eq(AgentMemoryRow::getUserId, user).eq(AgentMemoryRow::getAgentId, agent).eq(AgentMemoryRow::getId, id)
            .gt(AgentMemoryRow::getExpiresAt, now);
        return selectList(criteria).stream().map(storedRow -> {
            var mappedRow = new MemoryQueryRow();
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
            if (storedRow.getMemoryKey() != null) {
                mappedRow.setMemoryKey(storedRow.getMemoryKey());
            }
            if (storedRow.getContent() != null) {
                mappedRow.setContent(storedRow.getContent());
            }
            if (storedRow.getSourceMessageId() != null) {
                mappedRow.setSourceMessageId(storedRow.getSourceMessageId());
            }
            if (storedRow.getExpiresAt() != null) {
                mappedRow.setExpiresAt(
                    (storedRow.getExpiresAt() == null ? null : Timestamp.from(storedRow.getExpiresAt())));
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

    List<MemoryQueryRow> findAgentMemoryLocked(@Param("enterprise") String enterprise, @Param("user") String user,
                                               @Param("agent") String agent, @Param("id") String id,
                                               @Param("now") Timestamp now, @Param("lock") boolean lock);

    default List<Long> countAgentMemory(String enterprise, String user, String agent) {
        return List.of(selectCount(
            new LambdaQueryWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterprise)
                .eq(AgentMemoryRow::getUserId, user).eq(AgentMemoryRow::getAgentId, agent)));
    }

    default int insertAgentMemory(String id, String enterpriseId, String userId, String agentId, String memoryKey,
                                  String content, String sourceMessageId, Timestamp expiresAt, Timestamp time2,
                                  Timestamp time3) {
        var databaseRow = new AgentMemoryRow();
        databaseRow.setId(id);
        databaseRow.setEnterpriseId(enterpriseId);
        databaseRow.setUserId(userId);
        databaseRow.setAgentId(agentId);
        databaseRow.setMemoryKey(memoryKey);
        databaseRow.setContent(content);
        databaseRow.setSourceMessageId(sourceMessageId);
        databaseRow.setExpiresAt((expiresAt == null ? null : expiresAt.toInstant()));
        databaseRow.setRevision(1L);
        databaseRow.setCreatedAt((time2 == null ? null : time2.toInstant()));
        databaseRow.setUpdatedAt((time3 == null ? null : time3.toInstant()));
        return insert(databaseRow);
    }

    default int updateAgentMemory(String content, Timestamp expiresAt, Timestamp now, String enterpriseId,
                                  String userId, String id) {
        return update(new LambdaUpdateWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterpriseId)
            .eq(AgentMemoryRow::getUserId, userId).eq(AgentMemoryRow::getId, id)
            .set(AgentMemoryRow::getContent, content).set(AgentMemoryRow::getExpiresAt, expiresAt)
            .setIncrBy(AgentMemoryRow::getRevision, 1).set(AgentMemoryRow::getUpdatedAt, now));
    }

    default int deleteAgentMemory(String enterprise, String user, String agent, String id) {
        return delete(new LambdaQueryWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterprise)
            .eq(AgentMemoryRow::getUserId, user).eq(AgentMemoryRow::getAgentId, agent).eq(AgentMemoryRow::getId, id));
    }

    default int clearAgentMemory(String enterprise, String user, String agent) {
        return delete(new LambdaQueryWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterprise)
            .eq(AgentMemoryRow::getUserId, user).eq(AgentMemoryRow::getAgentId, agent));
    }

    default int expireAgentMemory(String enterprise, String user, String agent, Timestamp now) {
        return delete(new LambdaQueryWrapper<AgentMemoryRow>().eq(AgentMemoryRow::getEnterpriseId, enterprise)
            .eq(AgentMemoryRow::getUserId, user).eq(AgentMemoryRow::getAgentId, agent)
            .le(AgentMemoryRow::getExpiresAt, now));
    }

    int expireAgentMemory2(@Param("now") Timestamp now);

    int clearSourceConversationAgentMemory(@Param("enterprise") String enterprise,
                                           @Param("conversation") String conversation);
}
