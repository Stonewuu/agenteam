package com.stonewu.agenteam.mapper.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.execution.entity.JobLease;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 检查点保存加密状态文件的摘要与位置，不把框架原始正文写入公开接口。
 */
@Repository
public class RunCheckpointMapper {
    public record Checkpoint(long leaseVersion, String stateKey, JsonNode state) {
    }

    private final RunCheckpointSqlMapper statements;
    private final ResourceJson json;

    public RunCheckpointMapper(RunCheckpointSqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public void remove(String enterprise, String run) {
        statements.removeRunCheckpoint(enterprise, run);
    }

    public Optional<Checkpoint> find(String enterprise, String run) {
        return statements.findRunCheckpoint(enterprise, run).stream().map(row -> {

            var state = json.read(row.getStateJson());

            if (!json.hash(state).equals(row.getStateHash())) {
                throw new IllegalStateException("执行检查点内容不完整");
            }

            return new Checkpoint(row.getLeaseVersion(), row.getFrameworkStateKey(), state);

        }).findFirst();
    }

    public void save(JobLease lease, String stateKey, JsonNode state, Instant now) {
        statements.saveRunCheckpoint(lease.enterpriseId(), lease.runId(), lease.version(), json.write(state), stateKey,
            json.hash(state), timestamp(now));
    }
}
