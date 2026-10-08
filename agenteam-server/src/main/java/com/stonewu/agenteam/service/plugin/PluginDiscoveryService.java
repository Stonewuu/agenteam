package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.tool.ToolProviderMapper;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.tool.ToolCallControl;
import com.stonewu.agenteam.service.tool.provider.ToolProviderContext;
import com.stonewu.agenteam.service.tool.provider.ToolProviderRegistry;
import com.stonewu.tool.api.ToolSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 检查与实际调用使用同一来源接口；合集只读取已经发布的工具定义。
 */
@Service
public class PluginDiscoveryService {
    private final ToolProviderRegistry providers;
    private final ToolProviderMapper mapper;
    private final PluginToolMapper published;
    private final ResourceJson json;

    public PluginDiscoveryService(ToolProviderRegistry providers, ToolProviderMapper mapper, PluginToolMapper published,
                                  ResourceJson json) {
        this.providers = providers;
        this.mapper = mapper;
        this.published = published;
        this.json = json;
    }

    public List<ToolDefinition> discover(String enterprise, JsonNode config) {
        if (PluginConfigurationMapper.mode(config).equals("collection")) {
            var result = new ArrayList<ToolDefinition>();
            for (var selected : config.path("tools")) {
                var source = PluginConfigurationMapper.source(config, selected.path("sourceId").asText());
                published.list(enterprise, source.path("versionId").asText()).stream()
                    .filter(tool -> tool.enabled() && tool.definition().name().equals(selected.path("name").asText()))
                    .findFirst().ifPresent(tool -> result.add(tool.definition()));
            }
            return List.copyOf(result);
        }
        var connection = PluginConfigurationMapper.connection(config);
        var source = new ToolSource(connection.path("pluginType").asText(),
            connection.path("builtinCode").asText("remote"), null,
            connection.path("implementationVersion").asText("1"), json.object(connection));
        try (var control = new ToolCallControl(() -> {
        })) {
            var context = new ToolProviderContext(enterprise,
                Duration.ofSeconds(connection.path("timeoutSeconds").asInt(30)), control);
            return providers.require(source.type()).tools(source, context).stream().map(mapper::definition).toList();
        }
    }
}
