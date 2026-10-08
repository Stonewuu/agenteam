package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.agent.AgentHireMapper;
import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.resource.ResourceImpactMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.response.ResourceImpactView;
import com.stonewu.agenteam.model.resource.response.ResourceImpactView.Dependency;
import com.stonewu.agenteam.model.resource.response.ResourceSummaryView;
import com.stonewu.agenteam.model.resource.response.VersionSummaryView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.execution.RunLifecycleService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启停与恢复不改写发布正文；删除智能体时自动解除雇佣，未结束任务仍需先停止。
 */
@Service
public class ResourceLifecycleService {
    private final ResourcePolicy policy;
    private final ResourceAuthorizationService access;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceImpactMapper references;
    private final AgentHireMapper hires;
    private final AgentListingMapper listings;
    private final ResourceViewMapper views;
    private final AuditEventService audit;
    private final Clock clock;
    private final RunLifecycleService executions;

    public ResourceLifecycleService(ResourcePolicy policy, ResourceAuthorizationService access,
                                    EnterpriseAuthorizationService authorization,
                                    ResourceMapper resources, ResourceVersionMapper versions,
                                    ResourceImpactMapper references,
                                    AgentHireMapper hires, AgentListingMapper listings, ResourceViewMapper views,
                                    AuditEventService audit, Clock clock, RunLifecycleService executions) {
        this.policy = policy;
        this.access = access;
        this.authorization = authorization;
        this.resources = resources;
        this.versions = versions;
        this.references = references;
        this.hires = hires;
        this.listings = listings;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
        this.executions = executions;
    }

    public void authorize(AuthContext actor, String id, String action, boolean deleted) {
        policy.authorize(actor, id, action, true, deleted);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceImpactView impact(AuthContext actor, String id) {
        ResourceRecord resource;
        try {
            resource = policy.authorize(actor, id, "delete", true, false);
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().value() != 403 && denied.getStatusCode().value() != 404) {
                throw denied;
            }
            resource = policy.authorize(actor, id, "edit", true, false);
        }
        return impact(actor, resource);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceSummaryView status(AuthContext actor, String id, String status, long revision) {
        var resource = policy.authorize(actor, id, "edit", true, false);
        policy.revision(resource, revision);
        if (resource.source().equals("builtin") && !policy.enterpriseAdmin(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED",
                "只有企业管理员可以启停内置" + resource.kind().label() + "。");
        }
        if (status == null || !Set.of("active", "disabled").contains(status)) {
            throw ApiException.invalidField("status", "请选择启用或停用。");
        }
        if (status.equals(resource.status())) {
            return views.summary(actor, resource);
        }
        var now = clock.instant();
        resources.status(resource, status, now);
        int paused = status.equals("disabled") && resource.kind() == ResourceKind.AGENT ? hires.pauseForResource(
            actor.enterpriseId(), resource.id(), now) : 0;
        if (status.equals("disabled")) {
            executions.stopForResource(actor.enterpriseId(), resource.id(), null);
        }
        authorization.changed(actor, "resource.status", "resource", resource.id(),
            "修改" + resource.kind().label() + "启用状态",
            Map.of("kind", resource.kind().code(), "before", resource.status(), "after", status, "pausedHires",
                paused));
        return current(actor, resource.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(AuthContext actor, String id, long revision) {
        var resource = policy.authorize(actor, id, "delete", true, false);
        policy.revision(resource, revision);
        policy.editable(resource);
        var impact = impact(actor, resource);
        if (!impact.canDelete()) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_IN_USE",
                "此" + resource.kind().label() + "还有未结束的任务，请先停止任务。", Map.of("impact", impact), Map.of());
        }
        var now = clock.instant();
        int paused = references.pauseSchedules(actor.enterpriseId(), resource.id(), now);
        int terminated = resource.kind() == ResourceKind.AGENT ? hires.terminateForResource(actor.enterpriseId(),
            resource.id(), now) : 0;
        resources.markDeleted(resource, now);
        if (resource.kind() == ResourceKind.AGENT) {
            listings.unlist(actor.enterpriseId(), resource.id(), actor.userId(), now);
        }
        authorization.changed(actor, "resource.delete", "resource", resource.id(),
            "删除" + resource.kind().label() + "并保留恢复期内的数据",
            Map.of("kind", resource.kind().code(), "pausedSchedules", paused, "terminatedHires", terminated));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceSummaryView restore(AuthContext actor, String id, long revision) {
        var resource = policy.authorize(actor, id, "delete", true, true);
        policy.revision(resource, revision);
        if (resource.source().equals("builtin")) {
            throw new ApiException(HttpStatus.CONFLICT, "BUILTIN_RESOURCE_READ_ONLY",
                "内置" + resource.kind().label() + "不能执行此操作。");
        }
        if (resource.deletedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_NOT_DELETED",
                "此" + resource.kind().label() + "没有被删除，请重新加载列表。");
        }
        var now = clock.instant();
        resources.restore(resource, now);
        if (resource.kind() == ResourceKind.AGENT) {
            listings.unlist(actor.enterpriseId(), resource.id(), actor.userId(), now);
        }
        authorization.changed(actor, "resource.restore", "resource", resource.id(),
            "恢复" + resource.kind().label() + "为停用状态", Map.of("kind", resource.kind().code()));
        return current(actor, resource.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VersionSummaryView revoke(AuthContext actor, String id, String versionId, String reason, long revision) {
        var resource = policy.authorize(actor, id, "publish", true, false);
        policy.revision(resource, revision);
        policy.editable(resource);
        var version = versions.find(actor.enterpriseId(), versionId)
            .filter(value -> value.resourceId().equals(resource.id()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        String note = ResourceInput.text(reason, "reason", 500, true);
        if (version.status().equals("revoked")) {
            return views.version(version);
        }
        versions.revoke(actor.enterpriseId(), version.id(), clock.instant());
        executions.stopForVersion(actor.enterpriseId(), version.id());
        resources.advanceRevision(resource, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "resource.version.revoke", "resource_version", version.id(),
            "撤销" + resource.kind().label() + "版本的使用资格",
            Map.of("kind", resource.kind().code(), "resourceId", resource.id(), "reason", note));
        return views.version(versions.find(actor.enterpriseId(), version.id()).orElseThrow());
    }

    private ResourceImpactView impact(AuthContext actor, ResourceRecord resource) {
        long total = references.dependents(actor.enterpriseId(), resource.id());
        long activeHires = references.activeHires(actor.enterpriseId(), resource.id());
        long enabledSchedules = references.enabledSchedules(actor.enterpriseId(), resource.id());
        long activeRuns = references.activeRuns(actor.enterpriseId(), resource.id());
        List<Dependency> visible = new ArrayList<>();
        if (total > 0) {
            for (ResourceKind kind : ResourceKind.values()) {
                if (visible.size() == 100) {
                    break;
                }
                try {
                    var scope = access.scope(actor, kind.code(), kind.permission("view"), ResourceCapability.VIEW);
                    visible.addAll(
                        references.visible(actor.enterpriseId(), resource.id(), scope, 100 - visible.size()));
                } catch (ResponseStatusException denied) {
                    if (denied.getStatusCode().value() != 403 && denied.getStatusCode().value() != 404) {
                        throw denied;
                    }
                }
            }
        }
        boolean allowed = policy.actions(actor, List.of(resource)).get(resource.id()).contains("delete");
        return new ResourceImpactView(resource.id(), Long.toString(resource.revision()), List.copyOf(visible),
            Math.max(0, total - visible.size()),
            activeHires, enabledSchedules, activeRuns,
            allowed && !resource.source().equals("builtin") && activeRuns == 0);
    }

    private ResourceSummaryView current(AuthContext actor, String id) {
        return views.summary(actor, resources.find(actor.enterpriseId(), id, false, false).orElseThrow());
    }
}
