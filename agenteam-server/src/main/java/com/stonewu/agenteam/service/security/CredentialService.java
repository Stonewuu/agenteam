package com.stonewu.agenteam.service.security;

import com.stonewu.agenteam.mapper.security.CredentialMapper;
import com.stonewu.agenteam.mapper.security.CredentialSummaryMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.security.request.CredentialWriteRequest;
import com.stonewu.agenteam.model.security.response.CredentialSummaryView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 凭据修改与审计一起提交，仍被使用的凭据不能撤销。
 */
@Service
public class CredentialService {
    private final CredentialMapper secrets;
    private final CredentialSummaryMapper summaries;
    private final PayloadEncryption encryption;
    private final CredentialSecretValidation validation;
    private final EnterpriseAuthorizationService authorization;
    private final ListPagination pagination;
    private final AuditEventService audit;
    private final Clock clock;

    public CredentialService(CredentialMapper secrets, CredentialSummaryMapper summaries, PayloadEncryption encryption,
                             CredentialSecretValidation validation, EnterpriseAuthorizationService authorization,
                             ListPagination pagination, AuditEventService audit, Clock clock) {
        this.secrets = secrets;
        this.summaries = summaries;
        this.encryption = encryption;
        this.validation = validation;
        this.authorization = authorization;
        this.pagination = pagination;
        this.audit = audit;
        this.clock = clock;
    }

    public void authorize(AuthContext actor, String id, boolean mutation) {
        String permission = !mutation && !actor.permissions()
            .contains("credential.manage") ? "credential.view" : "credential.manage";
        authorization.requireEnterpriseScope(
            mutation ? authorization.lockAndRequire(actor, permission) : authorization.require(actor, permission));
        if (id != null && summaries.find(actor.enterpriseId(), id, mutation).isEmpty()) {
            throw ResourceAuthorizationService.unavailable();
        }
    }

    public PageResponse<CredentialSummaryView> list(AuthContext actor, String cursor, Integer requested) {
        authorize(actor, null, false);
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "credentials", "",
            "created_desc");
        return pagination.page(summaries.list(actor.enterpriseId(), pagination.read(cursor, binding), limit), limit,
            binding,
            value -> new PagePosition(Instant.parse(value.createdAt()), value.id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CredentialSummaryView create(AuthContext actor, CredentialWriteRequest input) {
        authorize(actor, null, true);
        validation.validate(input.kind(), input.secret());
        String id = UUID.randomUUID().toString(), name = ResourceInput.text(input.name(), "name", 80, true);
        secrets.store(id, actor.enterpriseId(), name, input.kind(),
            encryption.encrypt(input.secret(), binding(actor, id)), actor.userId(), false, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "credential.create", "credential", id, "创建连接凭据",
            Map.of("kind", input.kind()));
        return summaries.find(actor.enterpriseId(), id, false).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CredentialSummaryView rotate(AuthContext actor, String id, long revision, String secret) {
        var current = current(actor, id, revision);
        if (!current.status().equals("active")) {
            throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_REVOKED", "该凭据已撤销，请创建新的凭据。");
        }
        validation.validate(current.kind(), secret);
        secrets.store(current.id(), actor.enterpriseId(), current.name(), current.kind(),
            encryption.encrypt(secret, binding(actor, current.id())), actor.userId(), true, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "credential.rotate", "credential", current.id(),
            "轮换连接凭据", Map.of("kind", current.kind()));
        return summaries.find(actor.enterpriseId(), current.id(), false).orElseThrow();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void revoke(AuthContext actor, String id, long revision) {
        var current = current(actor, id, revision);
        if (current.referenceCount() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_IN_USE",
                "仍有可用资源引用此凭据，请先替换引用或停用相关资源。",
                Map.of("referenceCount", current.referenceCount()), Map.of());
        }
        summaries.revoke(actor.enterpriseId(), current.id(), actor.userId(), clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "credential.revoke", "credential", current.id(),
            "撤销未被使用的连接凭据", Map.of());
    }

    private CredentialSummaryView current(AuthContext actor, String id, long revision) {
        authorize(actor, id, true);
        var current = summaries.find(actor.enterpriseId(), id, true).orElseThrow();
        if (Long.parseLong(current.revision()) != revision) {
            throw ApiException.versionConflict(Long.parseLong(current.revision()));
        }
        return current;
    }

    private static String binding(AuthContext actor, String id) {
        return "credential:" + actor.enterpriseId() + ":" + id;
    }
}
