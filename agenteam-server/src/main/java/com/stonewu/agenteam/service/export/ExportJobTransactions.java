package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.export.ExportJobMapper;
import com.stonewu.agenteam.mapper.export.ExportPayloadMapper;
import com.stonewu.agenteam.mapper.file.FileMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.export.entity.ExportDefinition;
import com.stonewu.agenteam.model.export.entity.ExportPayload;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import com.stonewu.agenteam.model.file.entity.PreparedGeneratedFile;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 创建和结果登记均为短事务；文件写入或失败时不留下可下载的部分结果。
 */
@Service
public class ExportJobTransactions {
    private final ExportAccessService access;
    private final BackgroundJobMapper jobs;
    private final ExportJobMapper results;
    private final ExportPayloadMapper payloads;
    private final FileMapper files;
    private final EnterpriseMapper enterprises;
    private final AuditEventService audit;
    private final Clock clock;

    public ExportJobTransactions(ExportAccessService access, BackgroundJobMapper jobs, ExportJobMapper results,
                                 ExportPayloadMapper payloads, FileMapper files, EnterpriseMapper enterprises,
                                 AuditEventService audit, Clock clock) {
        this.access = access;
        this.jobs = jobs;
        this.results = results;
        this.payloads = payloads;
        this.files = files;
        this.enterprises = enterprises;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String create(AuthContext actor, ExportDefinition requested) {
        var definition = access.validate(actor, requested);
        String id = UUID.randomUUID().toString();
        try {
            jobs.enqueue(id, actor.enterpriseId(), actor.userId(), "export", "export:" + id,
                payloads.write(ExportPayload.requested(definition)), clock.instant());
        } catch (DuplicateKeyException active) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS",
                "已有一个导出正在处理，请等待完成后再导出。");
        }
        audit.record(actor.enterpriseId(), actor.user(), "export.create", "background_job", id, "创建数据导出任务",
            Map.of("type", definition.type()));
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean complete(JobLease lease, ExportSnapshot snapshot, PreparedGeneratedFile file) {
        if (!lease.kind().equals("export") || !file.enterpriseId().equals(lease.enterpriseId()) || !file.ownerUserId()
            .equals(lease.ownerUserId())
            || !file.purpose()
            .equals("export") || file.resourceId() != null || file.resourceVersionId() != null || file.runId() != null
            || !snapshot.definition().equals(payloads.read(lease.payloadJson()).definition())) {
            throw new IllegalArgumentException("导出结果归属或查询条件不正确");
        }
        var actor = access.actor(lease.enterpriseId(), lease.ownerUserId());
        var now = clock.instant();
        var payload = new ExportPayload(snapshot.definition(), snapshot.actorIds(), snapshot.rowCount(),
            snapshot.readAt().toString(), now.plusSeconds(86400).toString());
        access.requireActors(actor, payload, true);
        if (lease.exhausted() || !jobs.lockOwned(lease, clock.instant())) {
            return false;
        }
        files.generated(file, now);
        results.result(lease, file.id(), payloads.write(payload));
        if (!jobs.finish(lease, "completed", null, null, now, clock.instant())) {
            throw new IllegalStateException("导出工作资格已变化，结果未提交");
        }
        audit.record(actor.enterpriseId(), actor.user(), "export.completed", "background_job", lease.id(),
            "完成数据导出", Map.of("type", snapshot.definition().type(), "rowCount", snapshot.rowCount()));
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failed(JobLease lease, String code, String summary, boolean retry) {
        if (!lease.kind().equals("export")) {
            throw new IllegalArgumentException("工作类型不是导出");
        }
        enterprises.lockEnterprise(lease.enterpriseId());
        var now = clock.instant();
        if (!jobs.lockOwned(lease, now)) {
            return;
        }
        boolean again = retry && !lease.exhausted() && lease.attemptCount() < lease.maxAttempts();
        jobs.finish(lease, again ? "queued" : "failed", code, summary, again ? now.plusSeconds(30) : now, now);
    }
}
