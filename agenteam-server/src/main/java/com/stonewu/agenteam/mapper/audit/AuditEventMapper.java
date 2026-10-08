package com.stonewu.agenteam.mapper.audit;

import com.stonewu.agenteam.mapper.auth.IdentityQueryMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 只追加已完成的重要操作；调用方只传递允许记录的业务差异。
 */
@Repository
public class AuditEventMapper {

    private final AuditEventSqlMapper statements;

    private final IdentityQueryMapper identityQueryMapper;

    public AuditEventMapper(AuditEventSqlMapper statements, IdentityQueryMapper identityQueryMapper) {
        this.identityQueryMapper = identityQueryMapper;
        this.statements = statements;
    }

    public void append(String enterpriseId, String userId, String name, String action, String objectType,
                       String objectId, String summary, String detailJson, String requestId, Instant now) {
        String actorName = identityQueryMapper.appendEnterpriseMember(enterpriseId, userId).stream().findFirst()
            .orElse(name);
        statements.appendAuditEvent(UUID.randomUUID().toString(), enterpriseId, userId, actorName, action, objectType,
            objectId, summary, detailJson, requestId, Timestamp.from(now));
    }
}
