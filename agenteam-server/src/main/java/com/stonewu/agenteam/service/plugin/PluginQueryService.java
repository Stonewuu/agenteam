package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolViewMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.response.BuiltinPluginView;
import com.stonewu.agenteam.model.plugin.response.PluginToolView;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourcePolicy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 维护页面读取已检查清单；未检查的远程地址不会被当成已经发现工具。
 */
@Service
public class PluginQueryService {
    private final ResourcePolicy policy;
    private final EnterpriseAuthorizationService authorization;
    private final BuiltinPluginRegistry builtins;
    private final PluginCheckEvidence checks;
    private final PluginToolMapper tools;
    private final PluginToolViewMapper views;
    private final ResourceVersionMapper versions;
    private final PluginToolSources sources;

    public PluginQueryService(ResourcePolicy policy, EnterpriseAuthorizationService authorization,
                              BuiltinPluginRegistry builtins,
                              PluginCheckEvidence checks, PluginToolMapper tools, PluginToolViewMapper views,
                              ResourceVersionMapper versions, PluginToolSources sources) {
        this.policy = policy;
        this.authorization = authorization;
        this.builtins = builtins;
        this.checks = checks;
        this.tools = tools;
        this.views = views;
        this.versions = versions;
        this.sources = sources;
    }

    public List<BuiltinPluginView> builtins(AuthContext actor) {
        authorization.require(actor, "plugin.view");
        return builtins.list();
    }

    public List<PluginToolView> tools(AuthContext actor, String id) {
        var resource = policy.authorize(actor, id, "view", false, false);
        if (resource.kind() != ResourceKind.PLUGIN) {
            throw ResourceAuthorizationService.unavailable();
        }
        if (PluginConfigurationMapper.mode(resource.config()).equals("collection")) {
            return sources.resolve(actor, resource).stream()
                .map(tool -> views.map(tool.entryId(), tool.entryId(), tool.definition(), true, tool.source().config(),
                    tool.source())).toList();
        }
        List<ToolDefinition> definitions = resource.config().path("pluginType").asText().equals("builtin")
            ? builtins.require(resource.config().path("builtinCode").asText()).tools() : checks.tools(resource);
        if (definitions.isEmpty() && resource.validation() == null && resource.publishedVersionId() != null
            && versions.find(actor.enterpriseId(), resource.publishedVersionId())
            .filter(version -> version.configHash().equals(resource.configHash())).isPresent()) {
            return tools.list(actor.enterpriseId(), resource.publishedVersionId()).stream()
                .map(tool -> views.map(tool, PluginConfigurationMapper.connection(resource.config()))).toList();
        }
        var selected = new HashSet<>(PluginConfigurationMapper.selectedNames(resource.config()));
        return definitions.stream().map(tool -> views.map(
            UUID.nameUUIDFromBytes((id + ":" + checks.connectionHash(resource.config()) + ":" + tool.name())
                .getBytes(StandardCharsets.UTF_8)).toString(), tool, selected.contains(tool.name()),
            PluginConfigurationMapper.connection(resource.config()))).toList();
    }

}
