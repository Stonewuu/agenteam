package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.RunAttemptRow;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.execution.response.RunAttemptView;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

@Repository
public class RunAttemptMapper {
    private final RunAttemptSqlMapper statements;

    public RunAttemptMapper(RunAttemptSqlMapper statements) {
        this.statements = statements;
    }

    public void fail(RunRecord run, String code, String message, Instant now) {
        statements.failRunAttempt(code, message, timestamp(now), run.enterpriseId(), run.id(), run.currentAttemptNo());
    }

    public Map<String, String> outputMessages(String enterprise, String user, String conversation,
                                              Set<String> attempts) {
        return statements.outputMessages(enterprise, user, conversation, attempts).stream()
            .collect(Collectors.toMap(RunAttemptRow::getId, RunAttemptRow::getOutputMessageId));
    }

    public void next(RunRecord run, String output) {
        statements.nextRunAttempt(UUID.randomUUID().toString(), run.enterpriseId(), run.id(),
            run.currentAttemptNo() + 1, output);
    }

    public List<RunAttemptView> list(String enterprise, String run) {
        return statements.listRunAttempt(enterprise, run).stream().map(row -> {

            var start = instant(row.getStartedAt());

            var end = instant(row.getFinishedAt());

            return new RunAttemptView(row.getId(), row.getAttemptNo(), row.getOutputMessageId(), row.getStatus(),
                start == null ? null : start.toString(), end == null ? null : end.toString(), row.getErrorSummary());

        }).toList();
    }
}
