package com.stonewu.agenteam.service.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.resource.*;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.request.DraftWriteRequest;
import com.stonewu.agenteam.model.resource.request.ResourceCreateRequest;
import com.stonewu.agenteam.model.resource.response.ResourceDetailView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.plugin.PluginCheckEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 草稿和标签一起保存，修改不会改变已发布版本、既有雇佣或历史会话。
 */
@Service
public class ResourceDraftService {
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final TagMapper tags;
    private final TagService selections;
    private final ResourcePolicy policy;
    private final ResourceConfigurationService configurations;
    private final CredentialBindingService credentials;
    private final ResourceCopyConfiguration copies;
    private final ResourceJson json;
    private final ResourceViewMapper views;
    private final AuditEventService audit;
    private final Clock clock;
    private final PluginCheckEvidence pluginChecks;

    public ResourceDraftService(ResourceMapper resources, ResourceVersionMapper versions, TagMapper tags,
                                TagService selections, ResourcePolicy policy,
                                ResourceConfigurationService configurations, CredentialBindingService credentials,
                                ResourceCopyConfiguration copies,
                                ResourceJson json, ResourceViewMapper views, AuditEventService audit, Clock clock,
                                PluginCheckEvidence pluginChecks) {
        this.resources = resources;
        this.versions = versions;
        this.tags = tags;
        this.selections = selections;
        this.policy = policy;
        this.configurations = configurations;
        this.credentials = credentials;
        this.copies = copies;
        this.json = json;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
        this.pluginChecks = pluginChecks;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetailView create(AuthContext actor, ResourceCreateRequest value) {
        var kind = ResourceInput.kind(value.kind());
        policy.authorizeCreation(actor, kind);
        return create(actor, kind, value.name(), value.description(), value.tagIds(),
            configurations.draft(actor, kind, value.config()), Map.of(), null, "created");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetailView importSkill(AuthContext actor, String name, String description, JsonNode config,
                                          Map<String, List<String>> errors) {
        policy.authorizeCreation(actor, ResourceKind.SKILL);
        return create(actor, ResourceKind.SKILL, name, description, List.of(),
            configurations.draft(ResourceKind.SKILL, json.object(config)), errors, null, "imported");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetailView save(AuthContext actor, String id, long revision, DraftWriteRequest value) {
        var resource = policy.authorize(actor, id, "edit", true, false);
        policy.revision(resource, revision);
        policy.editable(resource);
        JsonNode config = configurations.draft(actor, resource.kind(), value.config());
        credentials.validate(actor, resource.kind(), resource.config(), config);
        var selected = selections.validateSelection(actor.enterpriseId(), value.tagIds());
        resources.draft(resource, ResourceInput.text(value.name(), "name", 80, true),
            ResourceInput.text(value.description(), "description", 500, false),
            configurations.subtype(resource.kind(), config), config, actor.userId(), clock.instant());
        if (resource.kind() == ResourceKind.PLUGIN) {
            resources.validation(actor.enterpriseId(), id, pluginChecks.retained(resource, config));
        }
        tags.replace(actor.enterpriseId(), id, selected);
        audit.record(actor.enterpriseId(), actor.user(), "resource.draft.save", "resource", id,
            "保存" + resource.kind().label() + "草稿", Map.of("kind", resource.kind().code()));
        return views.detail(actor, resources.find(actor.enterpriseId(), id, false, false).orElseThrow());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetailView copy(AuthContext actor, String id, String name) {
        var original = policy.authorize(actor, id, "view", true, false);
        policy.authorizeCreation(actor, original.kind());
        var copied = copies.copy(actor, original);
        var selected = tags.forResource(actor.enterpriseId(), id).stream().map(tag -> tag.id()).toList();
        return create(actor, original.kind(), name, original.description(), selected,
            configurations.draft(actor, original.kind(), copied.config()), copied.fieldErrors(), id, "created");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetailView loadVersion(AuthContext actor, String id, String versionId, long revision) {
        var resource = policy.authorize(actor, id, "edit", true, false);
        policy.revision(resource, revision);
        policy.editable(resource);
        var version = versions.find(actor.enterpriseId(), versionId)
            .filter(item -> item.resourceId().equals(resource.id()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        JsonNode config = configurations.draft(actor, resource.kind(), version.config());
        credentials.validate(actor, resource.kind(), resource.config(), config);
        resources.draft(resource, version.name(), version.description(),
            configurations.subtype(resource.kind(), config), config, actor.userId(), clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "resource.draft.load_version", "resource", id,
            "将历史版本内容保存为新草稿", Map.of("versionId", versionId));
        return views.detail(actor, resources.find(actor.enterpriseId(), id, false, false).orElseThrow());
    }

    private ResourceDetailView create(AuthContext actor, ResourceKind kind, String name, String description,
                                      List<String> tagIds,
                                      JsonNode config, Map<String, List<String>> errors, String copiedFrom,
                                      String source) {
        credentials.validate(actor, kind, null, config);
        var selected = selections.validateSelection(actor.enterpriseId(), tagIds);
        String id = UUID.randomUUID().toString();
        resources.create(id, actor.enterpriseId(), kind, ResourceInput.text(name, "name", 80, true),
            ResourceInput.text(description, "description", 500, false),
            configurations.subtype(kind, config), actor.userId(), source, config, clock.instant());
        tags.replace(actor.enterpriseId(), id, selected);
        if (!errors.isEmpty()) {
            resources.validation(actor.enterpriseId(), id,
                json.tree(Map.of("configHash", json.hash(config), "fieldErrors", errors)));
        }
        audit.record(actor.enterpriseId(), actor.user(),
            copiedFrom != null ? "resource.copy" : source.equals("imported") ? "skill.import" : "resource.create",
            "resource", id,
            copiedFrom != null ? "复制" + kind.label() + "为新草稿" : source.equals(
                "imported") ? "导入技能为新草稿" : "创建" + kind.label() + "草稿",
            copiedFrom == null ? Map.of("kind", kind.code()) : Map.of("kind", kind.code(), "copiedFrom", copiedFrom));
        return views.detail(actor, resources.find(actor.enterpriseId(), id, false, false).orElseThrow());
    }
}
