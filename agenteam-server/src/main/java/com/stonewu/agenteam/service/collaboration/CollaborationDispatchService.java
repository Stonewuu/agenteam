package com.stonewu.agenteam.service.collaboration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.stonewu.agenteam.mapper.collaboration.CollaborationTaskMapper;
import com.stonewu.agenteam.mapper.collaboration.CollaborationWorkItemMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationTaskRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkItemRow;
import com.stonewu.agenteam.model.collaboration.entity.CollaborationWorkLease;
import com.stonewu.agenteam.model.execution.response.RunAccepted;
import com.stonewu.agenteam.service.execution.RunSubmissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** 数据库领取岗位工作；等待依赖、额度或同岗位执行时不创建替代会话。 */
@Service
public class CollaborationDispatchService {
    private final CollaborationWorkItemMapper works;
    private final CollaborationTaskMapper tasks;
    private final EnterpriseMapper enterprises;
    private final CollaborationAccessService access;
    private final CollaborationDependencyService dependencies;
    private final CollaborationContextService contexts;
    private final RunSubmissionService submissions;
    private final Clock clock;

    public CollaborationDispatchService(CollaborationWorkItemMapper works, CollaborationTaskMapper tasks, EnterpriseMapper enterprises,
            CollaborationAccessService access, CollaborationDependencyService dependencies, CollaborationContextService contexts,
            RunSubmissionService submissions, Clock clock) {
        this.works = works;
        this.tasks = tasks;
        this.enterprises = enterprises;
        this.access = access;
        this.dependencies = dependencies;
        this.contexts = contexts;
        this.submissions = submissions;
        this.clock = clock;
    }

    @Transactional
    public Optional<CollaborationWorkLease> claim(String owner) {
        var now = clock.instant();
        var candidates = works.selectPage(new Page<CollaborationWorkItemRow>(1, 1, false),
            new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getStatus, "ready")
                .isNull(CollaborationWorkItemRow::getActiveRunId)
                .and(value -> value.isNull(CollaborationWorkItemRow::getNextAttemptAt).or().le(CollaborationWorkItemRow::getNextAttemptAt, now))
                .and(value -> value.isNull(CollaborationWorkItemRow::getLeaseUntil).or().le(CollaborationWorkItemRow::getLeaseUntil, now))
                .orderByAsc(CollaborationWorkItemRow::getUpdatedAt, CollaborationWorkItemRow::getId)).getRecords();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        var work = candidates.getFirst();
        int changed = works.update(new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, work.getEnterpriseId())
            .eq(CollaborationWorkItemRow::getId, work.getId()).eq(CollaborationWorkItemRow::getLeaseVersion, work.getLeaseVersion())
            .eq(CollaborationWorkItemRow::getStatus, "ready").isNull(CollaborationWorkItemRow::getActiveRunId)
            .and(value -> value.isNull(CollaborationWorkItemRow::getLeaseUntil).or().le(CollaborationWorkItemRow::getLeaseUntil, now))
            .set(CollaborationWorkItemRow::getLeaseOwner, owner).set(CollaborationWorkItemRow::getLeaseUntil, now.plusSeconds(30))
            .set(CollaborationWorkItemRow::getLeaseVersion, work.getLeaseVersion() + 1));
        return changed == 1 ? Optional.of(new CollaborationWorkLease(work.getEnterpriseId(), work.getId(), work.getLeaseVersion() + 1, owner)) : Optional.empty();
    }

    @Transactional
    public RunAccepted submit(CollaborationWorkLease lease) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var work = owned(lease);
        if (work == null) {
            return null;
        }
        var task = access.require(lease.enterpriseId(), work.getTaskId());
        var role = access.participant(task, work.getParticipantId());
        access.requireRole(task, role);
        String readiness = dependencies.readiness(task, work.getId());
        boolean resolveFailure = readiness.equals("blocked") && work.getId().equals(task.getOwnerWorkId())
            && work.getResumeReason().equals("dependencies_ready");
        resolveFailure |= work.getId().equals(task.getOwnerWorkId()) && work.getResumeReason().equals("input_changed");
        if (!readiness.equals("ready") && !resolveFailure) {
            changed(works.update(guard(lease).set(CollaborationWorkItemRow::getStatus, readiness)
                .set(CollaborationWorkItemRow::getLeaseOwner, null).set(CollaborationWorkItemRow::getLeaseUntil, null)));
            return null;
        }
        var context = contexts.freeze(task, work, work.getRunRound() + 1);
        var actor = access.prepareRole(task, role);
        return submissions.collaboration(actor, lease, context.getId(), context.getRunRound());
    }

    @Transactional
    public void failed(CollaborationWorkLease lease, String code, boolean retry) {
        enterprises.lockEnterprise(lease.enterpriseId()).orElseThrow();
        var work = owned(lease);
        if (work == null) {
            return;
        }
        changed(works.update(guard(lease).set(CollaborationWorkItemRow::getStatus, retry ? "ready" : "blocked")
            .set(CollaborationWorkItemRow::getErrorCode, code).set(CollaborationWorkItemRow::getNextAttemptAt, retry ? clock.instant().plusSeconds(
                Math.min(60, (5L << Math.min(3, lease.version() - 1)) + ThreadLocalRandom.current().nextLong(0, 5))) : null)
            .set(CollaborationWorkItemRow::getLeaseOwner, null).set(CollaborationWorkItemRow::getLeaseUntil, null)
            .set(CollaborationWorkItemRow::getUpdatedAt, clock.instant())));
        if (!retry) {
            tasks.update(new LambdaUpdateWrapper<CollaborationTaskRow>().eq(CollaborationTaskRow::getEnterpriseId, lease.enterpriseId())
                .eq(CollaborationTaskRow::getId, work.getTaskId()).in(CollaborationTaskRow::getStatus, "active", "waiting_input", "waiting_approval", "blocked")
                .set(CollaborationTaskRow::getStatus, "blocked").set(CollaborationTaskRow::getErrorCode, code)
                .setIncrBy(CollaborationTaskRow::getRevision, 1).set(CollaborationTaskRow::getUpdatedAt, clock.instant()));
        }
    }

    private CollaborationWorkItemRow owned(CollaborationWorkLease lease) {
        return works.selectOne(new LambdaQueryWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, lease.enterpriseId())
            .eq(CollaborationWorkItemRow::getId, lease.workId()).eq(CollaborationWorkItemRow::getLeaseOwner, lease.owner())
            .eq(CollaborationWorkItemRow::getLeaseVersion, lease.version()).gt(CollaborationWorkItemRow::getLeaseUntil, clock.instant())
            .eq(CollaborationWorkItemRow::getStatus, "ready").isNull(CollaborationWorkItemRow::getActiveRunId));
    }

    private LambdaUpdateWrapper<CollaborationWorkItemRow> guard(CollaborationWorkLease lease) {
        return new LambdaUpdateWrapper<CollaborationWorkItemRow>().eq(CollaborationWorkItemRow::getEnterpriseId, lease.enterpriseId())
            .eq(CollaborationWorkItemRow::getId, lease.workId()).eq(CollaborationWorkItemRow::getLeaseOwner, lease.owner())
            .eq(CollaborationWorkItemRow::getLeaseVersion, lease.version()).gt(CollaborationWorkItemRow::getLeaseUntil, clock.instant())
            .eq(CollaborationWorkItemRow::getStatus, "ready").isNull(CollaborationWorkItemRow::getActiveRunId);
    }

    private void changed(int count) {
        if (count != 1) {
            throw new IllegalStateException("协作工作领取已经过期");
        }
    }
}
