package com.stonewu.agenteam.mapper.memory;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.memory.entity.MemoryAgentRecord;
import com.stonewu.agenteam.model.memory.entity.MemoryQueryRow;
import com.stonewu.agenteam.model.memory.entity.MemoryRecord;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

@Repository
public class MemoryMapper {
    private final MemorySqlMapper statements;

    public MemoryMapper(MemorySqlMapper statements) {
        this.statements = statements;
    }

    public Optional<MemoryRecord> find(String enterprise, String user, String agent, String id, Instant now,
                                       boolean lock) {
        return statements.findAgentMemory(enterprise, user, agent, id, timestamp(now), lock).stream().map(this::map)
            .findFirst();
    }

    public List<MemoryRecord> list(String enterprise, String user, String agent, Instant now, PagePosition after,
                                   int limit) {
        return statements.listMemories(enterprise, user, agent, now, after, limit + 1).stream().map(this::map).toList();
    }

    public List<MemoryAgentRecord> agents(String enterprise, String user, Instant now, String query, PagePosition after,
                                          int limit) {
        return statements.listAgents(enterprise, user, now, query, after, limit + 1).stream().map(rows ->
            new MemoryAgentRecord(rows.getAgentId(), rows.getName(), rows.getMemoryCount(),
                instant(rows.getUpdatedAt()), rows.getAgentIcon(), rows.getAgentColor())).toList();
    }

    public long count(String enterprise, String user, String agent) {
        return DataAccessUtils.nullableSingleResult(statements.countAgentMemory(enterprise, user, agent));
    }

    public void insert(MemoryRecord value) {
        statements.insertAgentMemory(value.id(), value.enterpriseId(), value.userId(), value.agentId(),
            value.memoryKey(), value.content(), value.sourceMessageId(), timestamp(value.expiresAt()),
            timestamp(value.createdAt()), timestamp(value.updatedAt()));
    }

    public void update(MemoryRecord before, String content, Instant now, Instant expiresAt) {
        statements.updateAgentMemory(content, timestamp(expiresAt), timestamp(now), before.enterpriseId(),
            before.userId(), before.id());
    }

    public void delete(String enterprise, String user, String agent, String id) {
        statements.deleteAgentMemory(enterprise, user, agent, id);
    }

    public void clear(String enterprise, String user, String agent) {
        statements.clearAgentMemory(enterprise, user, agent);
    }

    public void expire(String enterprise, String user, String agent, Instant now) {
        statements.expireAgentMemory(enterprise, user, agent, timestamp(now));
    }

    public int expire(Instant now) {
        return statements.expireAgentMemory2(timestamp(now));
    }

    public void clearSourceConversation(String enterprise, String conversation) {
        statements.clearSourceConversationAgentMemory(enterprise, conversation);
    }

    private MemoryRecord map(MemoryQueryRow row) {
        return new MemoryRecord(row.getId(), row.getEnterpriseId(), row.getUserId(), row.getAgentId(),
            row.getMemoryKey(), row.getContent(),
            row.getSourceMessageId(), instant(row.getExpiresAt()), row.getRevision(), instant(row.getCreatedAt()),
            instant(row.getUpdatedAt()));
    }
}
