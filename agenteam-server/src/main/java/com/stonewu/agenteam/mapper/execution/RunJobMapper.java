package com.stonewu.agenteam.mapper.execution;

import com.stonewu.agenteam.model.execution.entity.JobLease;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stonewu.agenteam.mapper.execution.ExecutionJson.instant;
import static com.stonewu.agenteam.mapper.execution.ExecutionJson.timestamp;

/**
 * 仅领取执行任务，不能误领邮件或其他业务的工作。
 */
@Repository
public class RunJobMapper {
    private final RunJobSqlMapper statements;

    public RunJobMapper(RunJobSqlMapper statements) {
        this.statements = statements;
    }

    public void enqueue(String enterprise, String user, String run, Instant now) {
        statements.enqueueBackgroundJob(UUID.randomUUID().toString(), enterprise, user, "run:" + run, run,
            timestamp(now));
    }

    public Optional<JobLease> claim(String owner, Instant now, Instant until) {
        var ids = statements.claimBackgroundJob(timestamp(now));
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        String id = ids.getFirst();
        statements.assignLease(owner, timestamp(until), timestamp(now), id);
        return statements.claimBackgroundJob3(id).stream().map(
            row -> new JobLease(row.getId(), row.getEnterpriseId(), row.getOwnerUserId(), row.getRunId(),
                row.getLeaseOwner(), row.getLeaseVersion(), instant(row.getLeaseUntil()))).findFirst();
    }

    public boolean valid(JobLease lease, Instant now, boolean lock) {
        var ids = statements.validBackgroundJob(lease.id(), lease.enterpriseId(), lease.userId(), lease.owner(),
            lease.version(), timestamp(now), lock);
        return !ids.isEmpty();
    }

    public boolean renew(JobLease lease, Instant now, Instant until) {
        return statements.renewBackgroundJob(timestamp(now), timestamp(until), lease.id(), lease.owner(),
            lease.version()) == 1;
    }

    public void finish(String enterprise, String run, String status, String code, String error, Instant now) {
        statements.finishBackgroundJob(status, code, error, timestamp(now), enterprise, "run:" + run);
    }

    public List<JobLease> expired(Instant now, int limit) {
        return statements.expiredBackgroundJob(timestamp(now), limit).stream().map(
            row -> new JobLease(row.getId(), row.getEnterpriseId(), row.getOwnerUserId(), row.getRunId(),
                row.getLeaseOwner(), row.getLeaseVersion(), instant(row.getLeaseUntil()))).toList();
    }

    public boolean expiredAndLocked(JobLease lease, Instant now) {
        return !statements.expiredAndLockedBackgroundJob(lease.id(), lease.owner(), lease.version(), timestamp(now))
            .isEmpty();
    }

    public void requeue(JobLease lease, Instant now) {
        statements.requeueBackgroundJob(timestamp(now), lease.id(), lease.version());
    }

    public void park(JobLease lease, Instant now) {
        statements.parkBackgroundJob(timestamp(now), lease.id(), lease.version());
    }

    public void resume(String enterprise, String run, Instant now) {
        statements.resumeBackgroundJob(timestamp(now), enterprise, "run:" + run);
    }

    public void retry(String enterprise, String run, Instant available, Instant now) {
        int changed = statements.retryBackgroundJob(timestamp(available), timestamp(now), enterprise, "run:" + run);
        if (changed != 1) {
            throw new IllegalStateException("当前后台任务已变化，不能安排新的尝试");
        }
    }

    public record EventBatch(String id, String hash) {
    }

    public EventBatch lastEventBatch(JobLease lease) {
        return DataAccessUtils.nullableSingleResult(statements.lastEventBatchBackgroundJob(lease.id()).stream()
            .map(row -> new EventBatch(row.getLastEventBatchId(), row.getLastEventBatchHash())).toList());
    }

    public void savedEventBatch(JobLease lease, String id, String hash) {
        statements.savedEventBatchBackgroundJob(id, hash, lease.id(), lease.version());
    }

    public List<JobLease> queuedBefore(Instant boundary, int limit) {
        return statements.queuedBeforeBackgroundJob(timestamp(boundary), limit).stream().map(
            row -> new JobLease(row.getId(), row.getEnterpriseId(), row.getOwnerUserId(), row.getRunId(), null, 0,
                null)).toList();
    }
}
