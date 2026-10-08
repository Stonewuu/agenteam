package com.stonewu.agenteam.service.export;

import com.stonewu.agenteam.mapper.export.ExportPayloadMapper;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.export.entity.ExportSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 所有导出行来自同一个数据库读取快照，权限与数据共同读取，事务内不写文件。
 */
@Service
public class ExportSnapshotService {
    private final ExportAccessService access;
    private final ExportPayloadMapper payloads;
    private final ExportTypeRegistry types;
    private final Clock clock;

    public ExportSnapshotService(ExportAccessService access, ExportPayloadMapper payloads,
                                   ExportTypeRegistry types, Clock clock) {
        this.access = access;
        this.payloads = payloads;
        this.types = types;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 90)
    public ExportSnapshot read(JobLease lease) {
        if (!lease.kind().equals("export")) {
            throw ExportAccessService.unavailable();
        }
        var definition = payloads.read(lease.payloadJson()).definition();
        var actor = access.actor(lease.enterpriseId(), lease.ownerUserId());
        var scopes = access.authorize(actor, definition, false);
        var when = clock.instant();
        return types.require(definition).read(actor, definition, scopes, when);
    }
}
