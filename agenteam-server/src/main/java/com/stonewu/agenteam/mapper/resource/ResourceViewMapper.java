package com.stonewu.agenteam.mapper.resource;

import com.stonewu.agenteam.mapper.agent.AgentListingMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.model.agent.response.AgentListingView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.response.ActorView;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.model.resource.response.*;
import com.stonewu.agenteam.service.resource.FixedDependencyService;
import com.stonewu.agenteam.service.resource.ResourceConfigurationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 只把调用者已经获准维护的数据转换为详情；员工介绍不会使用此映射器。
 */
@Component
public class ResourceViewMapper {
    private final ResourceVersionMapper versions;
    private final TagMapper tags;
    private final ResourceJson json;
    private final ResourceAuthorizationMapper grants;
    private final ResourcePolicy policy;
    private final ResourceConfigurationService configurations;
    private final FixedDependencyService dependencies;
    private final AgentListingMapper listings;
    private final ResourceConnectionCheckMapper connectionChecks;
    private final PluginConfigurationMapper pluginConfigs;

    public ResourceViewMapper(ResourceVersionMapper versions, TagMapper tags, ResourceJson json,
                              ResourceAuthorizationMapper grants,
                              ResourcePolicy policy, ResourceConfigurationService configurations,
                              FixedDependencyService dependencies, AgentListingMapper listings,
                              ResourceConnectionCheckMapper connectionChecks, PluginConfigurationMapper pluginConfigs) {
        this.versions = versions;
        this.tags = tags;
        this.json = json;
        this.grants = grants;
        this.policy = policy;
        this.configurations = configurations;
        this.dependencies = dependencies;
        this.listings = listings;
        this.connectionChecks = connectionChecks;
        this.pluginConfigs = pluginConfigs;
    }

    public ResourceSummaryView summary(AuthContext actor, ResourceRecord resource) {
        return summaries(actor, List.of(resource)).getFirst();
    }

    public List<ResourceSummaryView> summaries(AuthContext actor, List<ResourceRecord> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        var ids = page.stream().map(ResourceRecord::id).toList();
        var publishedIds = page.stream().map(ResourceRecord::publishedVersionId).filter(Objects::nonNull).distinct()
            .toList();
        var published = versions.findMany(actor.enterpriseId(), publishedIds);
        var selectedTags = tags.forResources(actor.enterpriseId(), ids);
        var listingValues = listings.findMany(actor.enterpriseId(),
            page.stream().filter(item -> item.kind() == ResourceKind.AGENT).map(ResourceRecord::id).toList());
        var actions = policy.actions(actor, page);
        return page.stream().map(resource -> {
            var version = resource.publishedVersionId() == null ? null : published.get(resource.publishedVersionId());
            boolean changed = version != null && (!version.configHash().equals(resource.configHash()) || !version.name()
                .equals(resource.name()) || !version.description().equals(resource.description()));
            var listing = resource.kind() == ResourceKind.AGENT ? listingValues.getOrDefault(resource.id(),
                new AgentListingView(false, "automatic")) : null;
            return new ResourceSummaryView(resource.id(), Long.toString(resource.revision()),
                resource.createdAt().toString(), resource.updatedAt().toString(), resource.kind().code(),
                resource.name(), resource.description(), resource.config().path("icon").asText(),
                resource.config().path("color").asText(),
                new ActorView(resource.ownerUserId(), resource.ownerDisplayName()), resource.subtype(),
                resource.source(), resource.status(), version == null ? null : version(version), changed,
                List.copyOf(selectedTags.getOrDefault(resource.id(), List.of())), actions.get(resource.id()), listing);
        }).toList();
    }

    public ResourceDetailView detail(AuthContext actor, ResourceRecord resource) {
        List<ResourceGrantSpec> visibleGrants = policy.grantManager(actor, resource) ? grants.grants(
            actor.enterpriseId(), resource.id()) : List.of();
        return new ResourceDetailView(summary(actor, resource), json.object(
            resource.kind() == ResourceKind.PLUGIN ? pluginConfigs.normalize(actor.enterpriseId(),
                resource.config()) : resource.config()), visibleGrants, configurations.errors(resource),
            connectionChecks.lastResult(resource));
    }

    public ResourceVersionView version(AuthContext actor, ResourceVersionRecord version) {
        var visible = new ArrayList<VersionDependencyView>();
        for (var binding : versions.dependencies(actor.enterpriseId(), version.id())) {
            try {
                var value = dependencies.require(actor, binding);
                visible.add(new VersionDependencyView(value.resource().id(), value.version().id(), binding.kind(),
                    value.version().name(), binding.bindingKey()));
            } catch (ResponseStatusException denied) {
                if (!List.of(403, 404, 409).contains(denied.getStatusCode().value())) {
                    throw denied;
                }
            }
        }
        return new ResourceVersionView(version(version), json.object(version.config()), List.copyOf(visible));
    }

    public VersionSummaryView version(ResourceVersionRecord version) {
        return new VersionSummaryView(version.id(), version.versionNo(), version.name(), version.releaseNote(),
            version.status(),
            new ActorView(version.publishedBy(), version.publishedByName()), version.publishedAt().toString());
    }
}
