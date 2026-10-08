package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;

/**
 * 发布仅使用登记结构或刚验证的清单，事务内不连接远程服务。
 */
@Service
public class PluginVersionService {
    private final BuiltinPluginRegistry builtins;
    private final PluginCheckEvidence evidence;
    private final PluginToolMapper tools;
    private final PluginToolSources sources;

    public PluginVersionService(BuiltinPluginRegistry builtins, PluginCheckEvidence evidence, PluginToolMapper tools,
                                PluginToolSources sources) {
        this.builtins = builtins;
        this.evidence = evidence;
        this.tools = tools;
        this.sources = sources;
    }

    public void publish(AuthContext actor, ResourceRecord resource, String version, Instant now) {
        if (resource.kind() != ResourceKind.PLUGIN) {
            return;
        }
        if (PluginConfigurationMapper.modern(resource.config())) {
            for (var selected : sources.resolve(actor, resource)) {
                tools.publish(resource.enterpriseId(), version, selected.entryId(), selected.definition(),
                    selected.source(), now);
            }
            return;
        }
        var available = resource.config().path("pluginType").asText().equals("builtin")
            ? builtins.require(resource.config().path("builtinCode").asText()).tools() : evidence.tools(resource);
        var names = new HashSet<String>();
        resource.config().path("enabledToolNames").forEach(name -> names.add(name.asText()));
        tools.publish(resource.enterpriseId(), version,
            available.stream().filter(tool -> names.contains(tool.name())).toList(), now);
    }
}
