package com.stonewu.agenteam.mapper.background;

import com.stonewu.agenteam.model.background.entity.PublicJobQueryRow;
import com.stonewu.agenteam.model.background.entity.PublicJobRecord;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 在数据库查询中排除内部工作，不把邮件或运行凭据读出后再交给接口筛选。
 */
@Repository
public class PublicJobMapper {
    private final PublicJobSqlMapper statements;

    public PublicJobMapper(PublicJobSqlMapper statements) {
        this.statements = statements;
    }

    public Optional<PublicJobRecord> find(String enterprise, String id) {
        return statements.findBackgroundJob(enterprise, id).stream().map(this::map).findFirst();
    }

    public Optional<PublicJobRecord> forExportFile(String enterprise, String file) {
        return statements.forExportFileBackgroundJob(enterprise, file).stream().map(this::map).findFirst();
    }

    private PublicJobRecord map(PublicJobQueryRow row) {
        return new PublicJobRecord(row.getId(), row.getEnterpriseId(), row.getOwnerUserId(), row.getKind(),
            row.getPayloadJson(), row.getStatus(),
            row.getResultFileId(), row.getErrorSummary(), row.getCreatedAt().toInstant(),
            row.getUpdatedAt().toInstant());
    }
}
