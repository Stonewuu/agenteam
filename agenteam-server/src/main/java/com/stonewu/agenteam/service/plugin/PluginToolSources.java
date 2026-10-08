package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.entity.PluginToolSource;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.resource.entity.ResourceVersionRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按实际来源版本查找工具，客户端不能借合集构造未发布的工具或执行配置。
 */
@Service
public class PluginToolSources {
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final PluginToolMapper tools;
    private final PluginCheckEvidence evidence;

    public PluginToolSources(ResourceMapper resources, ResourceVersionMapper versions,
                             ResourceAuthorizationService access,
                             PluginToolMapper tools, PluginCheckEvidence evidence) {
        this.resources = resources;
        this.versions = versions;
        this.access = access;
        this.tools = tools;
        this.evidence = evidence;
    }

    public record Resolved(String entryId, ToolDefinition definition, PluginToolSource source) {
    }

    public record Builtin(ResourceRecord resource, ResourceVersionRecord version) {
    }

    public Builtin builtin(AuthContext actor, JsonNode source) {
        String id = source.path("id").asText(), versionId = source.path("versionId").asText();
        var resource = resources.find(actor.enterpriseId(), id, false, false)
            .filter(row -> row.kind() == ResourceKind.PLUGIN && row.source().equals("builtin"))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        access.requireUse(actor, id, "plugin");
        var version = versions.find(actor.enterpriseId(), versionId)
            .filter(row -> row.resourceId().equals(id) && row.status().equals("available")
                && row.config().path("pluginType").asText().equals("builtin"))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return new Builtin(resource, version);
    }

    public List<Resolved> resolve(AuthContext actor, ResourceRecord resource) {
        var config = resource.config();
        var result = new ArrayList<Resolved>();
        Map<String, Builtin> builtinSources = new HashMap<>();
        Map<String, List<ToolDefinition>> definitions = new HashMap<>();
        for (var source : config.path("sources")) {
            String id = source.path("id").asText();
            if (source.path("type").asText().equals("builtin")) {
                var builtin = builtin(actor, source);
                builtinSources.put(id, builtin);
                definitions.put(id,
                    tools.list(actor.enterpriseId(), builtin.version().id()).stream().filter(tool -> tool.enabled())
                        .map(tool -> tool.definition()).toList());
            } else {
                definitions.put(id, evidence.tools(resource));
            }
        }
        for (var selection : config.path("tools")) {
            var source = PluginConfigurationMapper.source(config, selection.path("sourceId").asText());
            String name = selection.path("name").asText();
            int timeout = Math.min(config.path("timeoutSeconds").asInt(30),
                selection.path("timeoutSeconds").asInt(120));
            ToolDefinition definition;
            PluginToolSource fixed;
            if (source.path("type").asText().equals("builtin")) {
                var builtin = builtinSources.get(source.path("id").asText());
                definition = definitions.get(source.path("id").asText()).stream()
                    .filter(tool -> tool.name().equals(name))
                    .findFirst().orElseThrow(
                        () -> ApiException.invalidField("config.tools", "所选版本已不再提供这个工具，请重新选择。"));
                ObjectNode execution = builtin.version().config().deepCopy();
                execution.put("timeoutSeconds", timeout);
                fixed = new PluginToolSource("builtin", builtin.resource().id(), builtin.version().id(),
                    builtin.version().config().path("implementationVersion").asText("1"), builtin.version().name(),
                    execution);
            } else {
                definition = definitions.get(source.path("id").asText()).stream()
                    .filter(tool -> tool.name().equals(name)).findFirst()
                    .orElseThrow(
                        () -> ApiException.invalidField("config.tools", "所选工具不在连接检查结果中，请重新检查。"));
                ObjectNode execution = PluginConfigurationMapper.connection(config).deepCopy();
                execution.put("timeoutSeconds", timeout);
                fixed = new PluginToolSource("mcp", resource.id(), evidence.connectionHash(config), null,
                    resource.name(), execution);
            }
            result.add(new Resolved(selection.path("id").asText(), definition, fixed));
        }
        return List.copyOf(result);
    }
}
