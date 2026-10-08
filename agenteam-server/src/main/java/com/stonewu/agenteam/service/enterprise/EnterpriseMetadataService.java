package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.enterprise.OrganizationMutationMapper;
import com.stonewu.agenteam.mapper.enterprise.OrganizationViewMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.request.EnterpriseUpdatePayload;
import com.stonewu.agenteam.model.enterprise.response.EnterpriseView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.AccountInputValidation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.usage.QuotaPeriodService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局企业管理只展示管理资料，不授予企业工作空间、成员或私有内容访问权。
 */
@Service
public class EnterpriseMetadataService {
    private final OrganizationViewMapper views;
    private final OrganizationMutationMapper mutations;
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final Clock clock;
    private final QuotaPeriodService periods;

    public EnterpriseMetadataService(OrganizationViewMapper views, OrganizationMutationMapper mutations,
                                     EnterpriseAuthorizationService authorization, PermissionMapper permissions,
                                     Clock clock, QuotaPeriodService periods) {
        this.views = views;
        this.mutations = mutations;
        this.authorization = authorization;
        this.permissions = permissions;
        this.clock = clock;
        this.periods = periods;
    }

    public List<EnterpriseView> list(UserEntity user) {
        return views.enterprises(user.id(), user.superAdmin()).stream().filter(enterprise -> user.superAdmin()
            || permissions.operationScope(user.id(), enterprise.id(), "enterprise.view").isPresent()).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EnterpriseView get(AuthContext actor) {
        authorization.lockAndRequire(actor, "enterprise.view");
        periods.current(actor.enterpriseId());
        return find(actor.enterpriseId());
    }

    public void authorizeUpdate(AuthContext actor) {
        authorization.requireEnterpriseScope(authorization.lockAndRequire(actor, "enterprise.manage"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EnterpriseView update(AuthContext actor, EnterpriseUpdatePayload payload, long revision,
                                 Set<String> fields) {
        authorizeUpdate(actor);
        periods.current(actor.enterpriseId());
        var before = find(actor.enterpriseId());
        if (!before.revision().equals(Long.toString(revision))) {
            throw ApiException.versionConflict(Long.parseLong(before.revision()));
        }
        String name = fields.contains("name") ? EnterpriseValidation.name(payload.name(), "企业名称",
            80) : before.name();
        String description = fields.contains("description") ? EnterpriseValidation.description(
            payload.description()) : before.description();
        String email = fields.contains(
            "contactEmail") ? (payload.contactEmail() == null ? null : AccountInputValidation.email(
            payload.contactEmail(), "contactEmail")) : before.contactEmail();
        String timezone = fields.contains("timezone") ? AccountInputValidation.timezone(
            payload.timezone()) : before.timezone();
        int retention = fields.contains("retentionDays") ? payload.retentionDays() : before.retentionDays();
        if (retention < 90 || retention > 730) {
            throw ApiException.invalidField("retentionDays", "保留时间需要在 90～730 天之间。");
        }
        String pending = timezone.equals(before.quotaTimezone()) ? null : timezone;
        mutations.enterprise(actor.enterpriseId(),
            new EnterpriseUpdatePayload(name, description, email, timezone, retention), pending, revision,
            clock.instant());
        authorization.changed(actor, "enterprise.update", "enterprise", actor.enterpriseId(), "修改企业资料",
            Map.of("beforeName", before.name(), "afterName", name,
                "beforeTimezone", before.timezone(), "afterTimezone", timezone, "beforeRetentionDays",
                before.retentionDays(), "afterRetentionDays", retention));
        return find(actor.enterpriseId());
    }

    private EnterpriseView find(String id) {
        return views.enterprise(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "企业不存在或无法访问。"));
    }
}
