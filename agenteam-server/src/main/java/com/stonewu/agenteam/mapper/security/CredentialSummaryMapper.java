package com.stonewu.agenteam.mapper.security;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.security.entity.CredentialSummaryQueryRow;
import com.stonewu.agenteam.model.security.response.CredentialSummaryView;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 管理查询不读取密文；引用按仍可使用的插件和数据源资源计算。
 */
@Repository
public class CredentialSummaryMapper {
    private final CredentialSummarySqlMapper statements;

    public CredentialSummaryMapper(CredentialSummarySqlMapper statements) {
        this.statements = statements;
    }

    public Optional<CredentialSummaryView> find(String enterprise, String id, boolean lock) {
        return statements.findResource(enterprise, id, lock).stream().map(this::map).findFirst();
    }

    public List<CredentialSummaryView> list(String enterprise, PagePosition cursor, int limit) {
        return statements.listSummaries(enterprise, cursor, limit + 1).stream().map(this::map).toList();
    }

    public void revoke(String enterprise, String id, String actor, Instant now) {
        statements.revokeCredential(actor, Timestamp.from(now), enterprise, id);
    }

    private CredentialSummaryView map(CredentialSummaryQueryRow rows) {
        return new CredentialSummaryView(rows.getId(), Long.toString(rows.getRevision()),
            rows.getCreatedAt().toInstant().toString(),
            rows.getUpdatedAt().toInstant().toString(), rows.getName(), rows.getKind(), rows.getStatus(),
            rows.getReferenceCount());
    }
}
