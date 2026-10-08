package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolViewMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.plugin.response.ToolSourceView;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 第一阶段目录提供平台内置能力，版本选择始终读取对应的发布快照。
 */
@Service
public class ToolSourceCatalogService {
    private final BuiltinPluginRegistry builtins;
    private final ResourceMapper resources;
    private final PluginToolSources sources;
    private final PluginToolMapper tools;
    private final PluginToolViewMapper views;
    private final EnterpriseAuthorizationService authorization;
    private final ResourceJson json;
    private final ResourceVersionMapper versions;

    public ToolSourceCatalogService(BuiltinPluginRegistry builtins, ResourceMapper resources, PluginToolSources sources,
                                    PluginToolMapper tools, PluginToolViewMapper views,
                                    EnterpriseAuthorizationService authorization, ResourceJson json,
                                    ResourceVersionMapper versions) {
        this.builtins = builtins;
        this.resources = resources;
        this.sources = sources;
        this.tools = tools;
        this.views = views;
        this.authorization = authorization;
        this.json = json;
        this.versions = versions;
    }

    public List<ToolSourceView> list(AuthContext actor) {
        authorization.require(actor, "plugin.view");
        var result = new ArrayList<ToolSourceView>();
        for (var builtin : builtins.list()) {
            var resource = resources.find(actor.enterpriseId(),
                PluginConfigurationMapper.builtinResourceId(actor.enterpriseId(), builtin.code()), false, false);
            if (resource.isEmpty() || resource.get().publishedVersionId() == null) {
                continue;
            }
            try {
                var latest = latest(actor.enterpriseId(), resource.get().id());
                if (latest != null) {
                    result.add(version(actor, resource.get().id(), latest.id()));
                }
            } catch (ResponseStatusException unavailable) {
                if (!List.of(403, 404, 409).contains(unavailable.getStatusCode().value())) {
                    throw unavailable;
                }
            }
        }
        return List.copyOf(result);
    }

    private ResourceVersionRecord latest(String enterprise, String resourceId) {
        PagePosition cursor = null;
        while (true) {
            var page = versions.list(enterprise, resourceId, cursor, 30);
            var available = page.stream().filter(version -> version.status().equals("available")).findFirst();
            if (available.isPresent()) {
                return available.get();
            }
            if (page.size() <= 30) {
                return null;
            }
            var last = page.getLast();
            cursor = new PagePosition(last.publishedAt(), last.id());
        }
    }

    public ToolSourceView version(AuthContext actor, String resourceId, String versionId) {
        authorization.require(actor, "plugin.view");
        var builtin = sources.builtin(actor, json.tree(Map.of("id", resourceId, "versionId", versionId)));
        if (!builtin.resource().source().equals("builtin")) {
            throw ResourceAuthorizationService.unavailable();
        }
        var version = builtin.version();
        var config = version.config();
        return new ToolSourceView(resourceId, versionId, version.versionNo(), version.name(), version.description(),
            config.path("icon").asText(), config.path("color").asText(), "builtin", config.path("builtinCode").asText(),
            tools.list(actor.enterpriseId(), versionId).stream().filter(tool -> tool.enabled())
                .map(tool -> views.map(tool.id(), tool.definition(), true, config)).toList());
    }
}
