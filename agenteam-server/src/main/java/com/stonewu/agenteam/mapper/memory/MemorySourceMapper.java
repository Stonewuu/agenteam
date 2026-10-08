package com.stonewu.agenteam.mapper.memory;

import com.stonewu.agenteam.mapper.execution.ExecutionMessageSqlMapper;

import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MemorySourceMapper {
    public record Source(String conversationId, String runId) {
    }

    private final ExecutionMessageSqlMapper executionMessageSqlMapper;

    public MemorySourceMapper(ExecutionMessageSqlMapper executionMessageSqlMapper) {
        this.executionMessageSqlMapper = executionMessageSqlMapper;
    }

    public Optional<Source> find(String enterprise, String user, String agent, String message) {
        return executionMessageSqlMapper.findMemorySourceMessage(enterprise, user, agent, message).stream()
            .map(row -> new Source(row.getConversationId(), row.getRunId())).findFirst();
    }
}
