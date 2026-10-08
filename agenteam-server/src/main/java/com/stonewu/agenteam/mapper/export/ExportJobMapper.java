package com.stonewu.agenteam.mapper.export;

import com.stonewu.agenteam.model.background.entity.JobLease;
import org.springframework.stereotype.Repository;

/**
 * 结果文件与导出范围在完成事务中一起绑定，旧领取版本不能修改。
 */
@Repository
public class ExportJobMapper {
    private final ExportJobSqlMapper statements;

    public ExportJobMapper(ExportJobSqlMapper statements) {
        this.statements = statements;
    }

    public void result(JobLease lease, String file, String payload) {
        int changed = statements.resultBackgroundJob(file, payload, lease.enterpriseId(), lease.id(),
            lease.ownerUserId(), lease.leaseOwner(), lease.leaseVersion());
        if (changed != 1) {
            throw new IllegalStateException("导出工作资格已变化，结果未提交");
        }
    }
}
