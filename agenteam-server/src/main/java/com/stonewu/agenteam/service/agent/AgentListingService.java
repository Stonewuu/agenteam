package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.request.AgentListingRequest;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理上架、雇佣方式与使用范围，授权变化和上架状态同事务保存。
 */
@Service
public class AgentListingService {
    private final AgentListingMapper listings;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourcePolicy policy;
    private final AuditEventService audit;
    private final Clock clock;
    private final ResourceAuthorizationMapper authorizations;
    private final ResourceGrantService grants;

    public AgentListingService(AgentListingMapper listings, ResourceMapper resources, ResourceVersionMapper versions,
                               ResourcePolicy policy, AuditEventService audit, Clock clock,
                               ResourceAuthorizationMapper authorizations, ResourceGrantService grants) {
        this.listings = listings;
        this.resources = resources;
        this.versions = versions;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.authorizations = authorizations;
        this.grants = grants;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceRecord update(AuthContext actor, String id, AgentListingRequest value, long revision) {
        var resource = policy.authorize(actor, id, "publish", true, false);
        policy.revision(resource, revision);
        apply(actor, resource, value, false);
        resources.advanceRevision(resource, clock.instant());
        return resources.find(actor.enterpriseId(), id, false, false).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveDuringPublish(AuthContext actor, ResourceRecord resource, AgentListingRequest value,
                                  boolean grantsProvided) {
        apply(actor, resource, value, grantsProvided);
    }

    private void apply(AuthContext actor, ResourceRecord resource, AgentListingRequest value, boolean grantsProvided) {
        if (resource.kind() != ResourceKind.AGENT) {
            throw ApiException.invalidField("listing", "只有数字员工可以设置上架与雇佣策略。");
        }
        policy.editable(resource);
        if (value == null || value.listed() == null || value.hirePolicy() == null || !Set.of("automatic", "approval")
            .contains(value.hirePolicy())) {
            throw ApiException.invalidField("listing", "请选择是否上架以及雇佣方式。");
        }
        if (resource.publishedVersionId() == null) {
            throw ApiException.invalidField("listing", "请先发布数字员工，再设置上架信息。");
        }
        if (value.listed() && (!resource.status().equals("active") || versions.find(actor.enterpriseId(),
                resource.publishedVersionId())
            .filter(version -> version.resourceId().equals(resource.id()) && version.status().equals("available"))
            .isEmpty())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_UNAVAILABLE",
                "数字员工已停用或发布版本不可用，暂时不能上架。");
        }
        var before = listings.find(actor.enterpriseId(), resource.id());
        saveUseGrants(actor, resource, value, !before.listed(), grantsProvided);
        listings.save(actor.enterpriseId(), resource.id(), value.listed(), value.hirePolicy(), actor.userId(),
            clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "agent.listing.update", "resource", resource.id(),
            "修改数字员工的上架与雇佣方式",
            Map.of("before", before, "after", value));
    }

    private void saveUseGrants(AuthContext actor, ResourceRecord resource, AgentListingRequest value,
                               boolean newlyListed, boolean grantsProvided) {
        if (value.useGrants() != null && grantsProvided) {
            throw ApiException.invalidField("useGrants", "请只在一处设置员工使用范围。");
        }
        var current = authorizations.grants(actor.enterpriseId(), resource.id());
        List<ResourceGrantSpec> requested = value.useGrants();
        // 只有可以管理授权的人才能自动开放给全员，其他发布者保留当前使用范围。
        if (requested == null && !grantsProvided && value.listed() && newlyListed && policy.grantManager(actor,
            resource)
            && current.stream().noneMatch(grant -> grant.capability().equals("use"))) {
            requested = List.of(new ResourceGrantSpec("enterprise", actor.enterpriseId(), "use"));
        }
        if (requested == null) {
            return;
        }
        for (var grant : requested) {
            if (grant == null || !"use".equals(grant.capability())) {
                throw ApiException.invalidField("useGrants", "上架范围只能设置使用授权。");
            }
        }
        var combined = new ArrayList<>(current.stream().filter(grant -> !grant.capability().equals("use")).toList());
        combined.addAll(requested);
        grants.replaceDuringPublish(actor, resource.id(), combined);
    }
}
