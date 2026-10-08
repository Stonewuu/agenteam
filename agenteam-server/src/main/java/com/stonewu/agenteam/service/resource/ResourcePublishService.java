package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.request.ResourcePublishRequest;
import com.stonewu.agenteam.model.resource.response.ResourceVersionView;
import com.stonewu.agenteam.service.agent.AgentListingService;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import com.stonewu.agenteam.service.plugin.PluginVersionService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * 固定版本、依赖、发布指针、授权和上架一起提交，任一验证失败全部回滚。
 */
@Service
public class ResourcePublishService {
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourcePolicy policy;
    private final ResourceConfigurationService configurations;
    private final FixedDependencyService dependencies;
    private final ResourceGrantService grants;
    private final AgentListingService listings;
    private final ResourceViewMapper views;
    private final AuditEventService audit;
    private final Clock clock;
    private final PluginVersionService plugins;

    public ResourcePublishService(ResourceMapper resources, ResourceVersionMapper versions, ResourcePolicy policy,
                                  ResourceConfigurationService configurations, FixedDependencyService dependencies,
                                  ResourceGrantService grants,
                                  AgentListingService listings, ResourceViewMapper views, AuditEventService audit,
                                  Clock clock, PluginVersionService plugins) {
        this.resources = resources;
        this.versions = versions;
        this.policy = policy;
        this.configurations = configurations;
        this.dependencies = dependencies;
        this.grants = grants;
        this.listings = listings;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
        this.plugins = plugins;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceVersionView publish(AuthContext actor, String id, ResourcePublishRequest request, long revision) {
        var resource = policy.authorize(actor, id, "publish", true, false);
        policy.revision(resource, revision);
        policy.editable(resource);
        if (!resource.status().equals("active")) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DISABLED",
                "请先启用" + resource.kind().label() + "，再发布新版本。");
        }
        if (request == null) {
            throw ApiException.invalidField("body", "请填写发布说明。");
        }
        String note = ResourceInput.text(request.releaseNote(), "releaseNote", 500, true);
        configurations.publish(actor, resource);
        var fixed = dependencies.resolve(actor, resource);
        String versionId = UUID.randomUUID().toString();
        versions.publish(versionId, resource, note, actor.userId(), fixed, clock.instant());
        plugins.publish(actor, resource, versionId, clock.instant());
        resources.publish(resource, versionId, clock.instant());
        if (request.grants() != null) {
            grants.replaceDuringPublish(actor, id, request.grants());
        }
        if (request.listing() != null) {
            listings.saveDuringPublish(actor, resources.find(actor.enterpriseId(), id, false, true).orElseThrow(),
                request.listing(), request.grants() != null);
        }
        audit.record(actor.enterpriseId(), actor.user(), "resource.publish", "resource", id,
            "发布" + resource.kind().label() + "版本",
            Map.of("kind", resource.kind().code(), "versionId", versionId, "versionNo", resource.nextVersionNo(),
                "dependencyCount", fixed.size()));
        return views.version(actor, versions.find(actor.enterpriseId(), versionId).orElseThrow());
    }
}
