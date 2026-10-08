package com.stonewu.agenteam.mapper.execution;


import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.model.execution.entity.RunQueryRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 执行与尝试的数据库操作，所有写入由执行事务服务编排。
 */
@Repository
public class RunMapper {
    private final RunSqlMapper statements;
    private final ExecutionJson json;

    private final RunAttemptSqlMapper runAttemptSqlMapper;

    public RunMapper(RunSqlMapper statements, ExecutionJson json, RunAttemptSqlMapper runAttemptSqlMapper) {
        this.runAttemptSqlMapper = runAttemptSqlMapper;
        this.statements = statements;
        this.json = json;
    }

    public Optional<RunRecord> find(String enterprise, String id, boolean lock) {
        return statements.findAgentRun(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public void create(String id, String enterprise, String user, String conversation, String input, String output,
                       String version, String mode, JsonNode config, Instant now) {
        create(id, enterprise, user, conversation, input, output, version, mode, config, 1, now);
    }

    public void create(String id, String enterprise, String user, String conversation, String input, String output,
                       String version, String mode, JsonNode config, int maxAttempts, Instant now) {
        statements.createAgentRun(id, enterprise, user, conversation, input, output, version, mode, json.write(config),
            maxAttempts, timestamp(now));
        runAttemptSqlMapper.createRunAttempt(UUID.randomUUID().toString(), enterprise, id, output);
    }

    public int activeCount(String enterprise, String user) {
        return user == null ? DataAccessUtils.nullableSingleResult(statements.activeCountAgentRun(enterprise))
            : DataAccessUtils.nullableSingleResult(statements.countUserActiveRuns(enterprise, user));
    }

    public void start(RunRecord run, long lease, Instant now) {
        int changed = statements.startAgentRun(lease, timestamp(now), run.enterpriseId(), run.id());
        if (changed != 1) {
            throw new IllegalStateException("执行已经变化，不能再次开始");
        }
        statements.startRunAttempt(timestamp(now), run.enterpriseId(), run.id(), run.currentAttemptNo());
    }

    public void updateSequence(String enterprise, String id, long sequence) {
        statements.updateSequenceAgentRun(sequence, enterprise, id);
    }

    public void retry(RunRecord run, String output, Instant available, Instant now) {
        int changed = statements.retryAgentRun(output, timestamp(available), timestamp(now), run.enterpriseId(),
            run.id(), run.currentAttemptNo());
        if (changed != 1) {
            throw new IllegalStateException("当前执行已变化，不能安排新的尝试");
        }
    }

    public void requestCancel(RunRecord run, Instant now) {
        statements.requestCancelAgentRun(timestamp(now), run.enterpriseId(), run.id());
    }

    public void finish(RunRecord run, String status, String code, String message, Instant now) {
        int changed = statements.finishAgentRun(status, code, message, timestamp(now), run.enterpriseId(), run.id());
        if (changed != 1) {
            throw new IllegalStateException("执行已经结束，不能改写终态");
        }
        runAttemptSqlMapper.finishRunAttempt(status, code, message, timestamp(now), run.enterpriseId(), run.id(),
            run.currentAttemptNo());
    }

    public List<RunRecord> activeForEnterprise(String enterprise) {
        return statements.activeForEnterpriseAgentRun(enterprise).stream().map(this::map).toList();
    }

    public List<RunRecord> forConversation(String enterprise, String conversation) {
        return statements.forConversationAgentRun(enterprise, conversation).stream().map(this::map).toList();
    }

    public Optional<String> previousRun(String enterprise, String conversation) {
        return statements.previousRunAgentRun(enterprise, conversation).stream().findFirst();
    }

    public Optional<String> latestTerminal(String enterprise, String conversation) {
        return statements.latestTerminalAgentRun(enterprise, conversation).stream().findFirst();
    }

    private RunRecord map(RunQueryRow row) {
        return new RunRecord(row.getId(), row.getEnterpriseId(), row.getConversationId(), row.getUserId(),
            row.getInputMessageId(), row.getOutputMessageId(), row.getAgentVersionId(),
            row.getMode(), row.getStatus(), json.tree(row.getExecutionConfigJson()), row.getCurrentAttemptNo(),
            row.getMaxAttempts(), row.getLeaseVersion(), row.getHasStepErrors(), row.getLastSequence(),
            instant(row.getStartedAt()), instant(row.getFinishedAt()), instant(row.getCancelRequestedAt()),
            instant(row.getNextAttemptAt()),
            row.getErrorCode(), row.getErrorMessage(), instant(row.getCreatedAt()));
    }
}
