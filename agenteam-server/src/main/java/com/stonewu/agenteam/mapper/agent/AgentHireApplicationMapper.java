package com.stonewu.agenteam.mapper.agent;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.agent.entity.AgentHireApplication;
import com.stonewu.agenteam.model.agent.entity.AgentHireApplicationQueryRow;
import com.stonewu.agenteam.model.agent.response.AgentHireApplicationView;
import com.stonewu.agenteam.model.enterprise.response.ActorView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 申请只允许从待处理变成一个终态，同时释放唯一待处理标志。
 */
@Repository
public class AgentHireApplicationMapper {

    private final AgentHireApplicationSqlMapper statements;

    private final ResourceJson json;

    public AgentHireApplicationMapper(AgentHireApplicationSqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public Optional<AgentHireApplication> find(String enterprise, String id, boolean lock) {
        return statements.findAgentHireRequest(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public Optional<AgentHireApplication> pending(String enterprise, String user, String agent) {
        return statements.pendingAgentHireRequest(enterprise, user, agent).stream().map(this::map).findFirst();
    }

    public List<AgentHireApplication> list(String enterprise, String user, boolean includeOwn,
                                           ResourceQueryScope approval, PagePosition cursor, int limit) {
        return statements.listApplications(enterprise, user, includeOwn, approval, cursor, limit + 1).stream()
            .map(this::map).toList();
    }

    public AgentHireApplication create(String enterprise, String user, String agent, String note, Instant now,
                                       Instant expires) {
        String id = UUID.randomUUID().toString();
        statements.createAgentHireRequest(id, enterprise, user, agent, note, Timestamp.from(expires),
            Timestamp.from(now));
        return find(enterprise, id, false).orElseThrow();
    }

    public void finish(AgentHireApplication value, String status, String actor, String note, Instant now) {
        int updated = statements.finishAgentHireRequest(status, actor, note, Timestamp.from(now), value.enterpriseId(),
            value.id(), value.revision());
        if (updated != 1) {
            throw new IllegalStateException("已锁定申请的状态发生变化，当前决定未提交");
        }
    }

    public void expireFor(String enterprise, String user, String agent, Instant now) {
        statements.expireForAgentHireRequest(Timestamp.from(now), enterprise, user, agent);
    }

    public int expire(Instant now) {
        return statements.expireAgentHireRequest(Timestamp.from(now));
    }

    public AgentHireApplicationView view(AgentHireApplication value, Instant now, boolean canWithdraw,
                                         boolean canDecide) {
        String status = value.status().equals("pending") && !value.expiresAt()
            .isAfter(now) ? "expired" : value.status();
        List<String> actions = new ArrayList<>();
        if (status.equals("pending")) {
            if (canWithdraw) {
                actions.add("withdraw");
            }
            if (canDecide) {
                actions.add("approve");
                actions.add("reject");
            }
        }
        return new AgentHireApplicationView(value.id(), Long.toString(value.revision()), value.createdAt().toString(),
            value.updatedAt().toString(), value.agentId(), value.agentName(),
            new ActorView(value.userId(), value.applicantName()), status, value.requestNote(), value.decisionNote(),
            value.expiresAt().toString(), List.copyOf(actions), value.agentIcon(), value.agentColor());
    }

    private AgentHireApplication map(AgentHireApplicationQueryRow rows) {
        var config = json.read(rows.getAgentConfigJson());
        return new AgentHireApplication(rows.getId(), rows.getEnterpriseId(), rows.getUserId(), rows.getApplicantName(),
            rows.getAgentId(), rows.getAgentName(), rows.getStatus(), rows.getRequestNote(), rows.getDecisionNote(),
            rows.getRevision(), rows.getExpiresAt().toInstant(), rows.getCreatedAt().toInstant(),
            rows.getUpdatedAt().toInstant(), config.path("icon").asText(null), config.path("color").asText(null));
    }
}
