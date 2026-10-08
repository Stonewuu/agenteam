package com.stonewu.agenteam.mapper.background;

import com.stonewu.agenteam.model.background.entity.JobLease;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * 后台工作使用数据库领取与版本更新，不能用进程内队列代替持久记录。
 */
@Repository
public class BackgroundJobMapper {
    private final BackgroundJobSqlMapper statements;

    public BackgroundJobMapper(BackgroundJobSqlMapper statements) {
        this.statements = statements;
    }

    public void enqueue(String id, String enterpriseId, String userId, String kind, String dedupeKey, String payload,
                        Instant now) {
        statements.enqueueBackgroundJob(id, enterpriseId, userId, kind, dedupeKey, payload, Timestamp.from(now));
    }

    public Optional<JobLease> claim(String kind, String workerId, Instant now) {
        return claim(kind, workerId, now, null);
    }

    public Optional<JobLease> claimFileWork(String kind, String workerId, Instant now) {
        if (!Set.of("file_scan", "document_parse").contains(kind)) {
            throw new IllegalArgumentException("文件处理工作类型不正确");
        }
        var enterprises = statements.claimFileWorkEnterprise(kind, Timestamp.from(now));
        return enterprises.isEmpty() ? Optional.empty() : claim(kind, workerId, now, enterprises.getFirst());
    }

    private Optional<JobLease> claim(String kind, String workerId, Instant now, String enterprise) {
        var candidates = statements.claimBackgroundJob(kind, Timestamp.from(now), enterprise).stream().map(
            rows -> new JobLease(rows.getId(), rows.getEnterpriseId(), rows.getOwnerUserId(), rows.getKind(),
                rows.getPayloadJson(), rows.getAttemptCount(), rows.getMaxAttempts(), workerId,
                rows.getLeaseVersion() + 1, rows.getAttemptCount() >= rows.getMaxAttempts())).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var candidate = candidates.getFirst();
        int attempts = candidate.attemptCount() + (candidate.exhausted() ? 0 : 1);
        statements.assignLease(workerId, candidate.leaseVersion(), Timestamp.from(now.plusSeconds(leaseSeconds(kind))),
            Timestamp.from(now), attempts, candidate.id());
        return Optional.of(new JobLease(candidate.id(), candidate.enterpriseId(), candidate.ownerUserId(), kind,
            candidate.payloadJson(),
            attempts, candidate.maxAttempts(), workerId, candidate.leaseVersion(), candidate.exhausted()));
    }

    public void setAttemptLimit(String id, int maximum) {
        if (maximum < 1 || maximum > 1440) {
            throw new IllegalArgumentException("后台任务尝试次数超出范围");
        }
        statements.setAttemptLimitBackgroundJob(maximum, id);
    }

    public boolean renew(JobLease lease, Instant now) {
        return statements.renewBackgroundJob(Timestamp.from(now.plusSeconds(leaseSeconds(lease.kind()))), Timestamp.from(now), lease.id(),
            lease.leaseOwner(), lease.leaseVersion()) == 1;
    }

    public boolean lockOwned(JobLease lease, Instant now) {
        return !statements.lockOwnedBackgroundJob(lease.id(), lease.leaseOwner(), lease.leaseVersion(),
            Timestamp.from(now)).isEmpty();
    }

    private long leaseSeconds(String kind) {
        return Set.of("channel_delivery", "scheduled_action").contains(kind) ? 60 : 30;
    }

    public boolean finish(JobLease lease, String status, String errorCode, String summary, Instant availableAt,
                          Instant now) {
        return statements.finishBackgroundJob(status, errorCode, summary, Timestamp.from(availableAt),
            Timestamp.from(now), lease.id(), lease.leaseOwner(), lease.leaseVersion()) == 1;
    }
}
