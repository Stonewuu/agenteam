package com.stonewu.agenteam.service.permission;

import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceAccessRecord;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资源授权全部替换前验证每个主体，失败不能留下部分授权。
 */
@Service
public class ResourceGrantService {
    private final EnterpriseAuthorizationService authorization;
    private final ResourceAuthorizationService access;
    private final ResourceAuthorizationMapper resources;
    private final EnterpriseMapper enterprises;
    private final Clock clock;
    private final Map<String, ResourceGrantExtension> extensions;

    public ResourceGrantService(EnterpriseAuthorizationService authorization, ResourceAuthorizationService access,
                                ResourceAuthorizationMapper resources,
                                EnterpriseMapper enterprises, Clock clock, List<ResourceGrantExtension> extensions) {
        this.authorization = authorization;
        this.access = access;
        this.resources = resources;
        this.enterprises = enterprises;
        this.clock = clock;
        Map<String, ResourceGrantExtension> registered = new HashMap<>();
        for (var extension : extensions) {
            String type = extension.subjectType();
            if (type == null || type.isBlank() || Set.of("enterprise", "user").contains(type)
                || registered.putIfAbsent(type, extension) != null) {
                throw new IllegalArgumentException("资源授权扩展类型无效、重复或试图替换基础授权类型");
            }
        }
        this.extensions = Map.copyOf(registered);
    }

    public List<ResourceGrantSpec> list(AuthContext actor, String id) {
        access.requireGrantReader(actor, id);
        return resources.grants(actor.enterpriseId(), id);
    }

    public Optional<ResourceGrantExtension> extensionFor(String subjectType) {
        return Optional.ofNullable(extensions.get(subjectType == null ? "" : subjectType));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceAccessRecord replace(AuthContext actor, String id, long revision,
                                        List<ResourceGrantSpec> requested) {
        authorization.lockAndRequire(actor, "resource.grants.manage");
        var resource = access.requireGrantManager(actor, id, true);
        if (resource.revision() != revision) {
            throw ApiException.versionConflict(resource.revision());
        }
        apply(actor, resource, requested);
        resources.advanceRevision(actor.enterpriseId(), id, revision, clock.instant());
        return resources.find(actor.enterpriseId(), id, false).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceDuringPublish(AuthContext actor, String id, List<ResourceGrantSpec> requested) {
        authorization.lockAndRequire(actor, "resource.grants.manage");
        var resource = access.requireGrantManager(actor, id, true);
        apply(actor, resource, requested);
    }

    private void apply(AuthContext actor, ResourceAccessRecord resource, List<ResourceGrantSpec> requested) {
        String id = resource.id();
        var kind = ResourceKind.from(resource.kind());
        if (requested == null || requested.size() > 500) {
            throw ApiException.invalidField("grants", kind.label() + "授权最多 500 项。");
        }
        Set<ResourceGrantSpec> unique = new HashSet<>();
        for (var grant : requested) {
            if (grant == null || grant.subjectId() == null || grant.capability() == null || !Set.of("view", "use",
                "edit").contains(grant.capability())
                || !unique.add(grant)) {
                throw ApiException.invalidField("grants", "授权内容无效或存在重复项。");
            }
            boolean valid = switch (grant.subjectType() == null ? "" : grant.subjectType()) {
                case "enterprise" -> actor.enterpriseId().equals(grant.subjectId());
                case "user" -> enterprises.activeMemberExists(actor.enterpriseId(), grant.subjectId());
                default -> validateExtendedSubject(actor, grant);
            };
            if (!valid) {
                throw ApiException.invalidField("grants", "授权对象必须是本企业当前有效的成员、企业或已支持的其他对象。");
            }
        }
        var before = resources.grants(actor.enterpriseId(), id);
        for (var extension : extensions.values()) {
            extension.authorizeChange(actor, before, requested);
        }
        resources.replaceGrants(actor.enterpriseId(), id, actor.userId(), unique, clock.instant());
        authorization.changed(actor, "resource.grants.update", "resource", id, "修改" + kind.label() + "授权",
            Map.of("kind", kind.code(), "before", before, "after", requested));
    }

    private boolean validateExtendedSubject(AuthContext actor, ResourceGrantSpec grant) {
        var extension = extensions.get(grant.subjectType() == null ? "" : grant.subjectType());
        if (extension == null) {
            return false;
        }
        extension.validateSubject(actor, grant);
        return true;
    }
}
