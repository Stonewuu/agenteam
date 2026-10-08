package com.stonewu.agenteam.mapper.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.model.agent.entity.AgentHire;
import com.stonewu.agenteam.model.agent.entity.AgentHireQueryRow;
import com.stonewu.agenteam.model.agent.entity.AgentHireRow;
import com.stonewu.agenteam.model.agent.response.AgentHireView;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 查询始终绑定企业与本人，恢复和再次雇佣复用同一关系编号。
 */
@Repository
public class AgentHireMapper {

    private final AgentHireSqlMapper statements;

    public AgentHireMapper(AgentHireSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<AgentHire> find(String enterprise, String user, String id, boolean lock) {
        return statements.findAgentHire(enterprise, user, id, lock).stream().map(this::map).findFirst();
    }

    public Optional<AgentHire> forAgent(String enterprise, String user, String agent, boolean lock) {
        return statements.forAgentAgentHire(enterprise, user, agent, lock).stream().map(this::map).findFirst();
    }

    public AgentHire establish(String enterprise, String user, String agent, Instant now) {
        var existing = forAgent(enterprise, user, agent, true).orElse(null);
        if (existing != null) {
            if (!existing.status().equals("active")) {
                changeStatus(existing, "active", now);
            }
            return find(enterprise, user, existing.id(), false).orElseThrow();
        }
        String id = UUID.randomUUID().toString();
        statements.establishAgentHire(id, enterprise, user, agent, Timestamp.from(now));
        return find(enterprise, user, id, false).orElseThrow();
    }

    public void changeStatus(AgentHire hire, String status, Instant now) {
        int updated = statements.changeStatusAgentHire(status, Timestamp.from(now), hire.enterpriseId(), hire.userId(),
            hire.id(), hire.revision());
        if (updated != 1) {
            throw new IllegalStateException("已锁定雇佣关系的版本发生变化，当前修改未提交");
        }
    }

    public AgentHireView view(AgentHire value) {
        return new AgentHireView(value.id(), Long.toString(value.revision()), value.createdAt().toString(),
            value.updatedAt().toString(), value.agentId(), value.userId(), value.status(), value.hiredAt().toString(),
            value.lastUsedAt() == null ? null : value.lastUsedAt().toString());
    }

    public int pauseForResource(String enterprise, String agent, Instant now) {
        return statements.pauseForResourceAgentHire(Timestamp.from(now), enterprise, agent);
    }

    /**
     * 删除事务已锁定企业，解除所有用户的有效及暂停雇佣，保留历史记录。
     */
    public int terminateForResource(String enterprise, String agent, Instant now) {
        long expected = statements.selectCount(
            new LambdaQueryWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise)
                .eq(AgentHireRow::getAgentId, agent).in(AgentHireRow::getStatus, "active", "paused"));
        if (expected == 0) {
            return 0;
        }
        int changed = statements.update(
            new LambdaUpdateWrapper<AgentHireRow>().eq(AgentHireRow::getEnterpriseId, enterprise)
                .eq(AgentHireRow::getAgentId, agent).in(AgentHireRow::getStatus, "active", "paused")
                .set(AgentHireRow::getStatus, "terminated").set(AgentHireRow::getTerminatedAt, now)
                .set(AgentHireRow::getPausedAt, null).set(AgentHireRow::getUpdatedAt, now)
                .setIncrBy(AgentHireRow::getRevision, 1));
        if (changed != expected) {
            throw new IllegalStateException("删除智能体时，解除雇佣的数量与预期不一致");
        }
        return changed;
    }

    private AgentHire map(AgentHireQueryRow rows) {
        var used = rows.getLastUsedAt();
        return new AgentHire(rows.getId(), rows.getEnterpriseId(), rows.getUserId(), rows.getAgentId(),
            rows.getStatus(), rows.getRevision(), rows.getHiredAt().toInstant(), used == null ? null : used.toInstant(),
            rows.getCreatedAt().toInstant(), rows.getUpdatedAt().toInstant());
    }
}
