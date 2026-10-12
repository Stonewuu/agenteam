package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import com.stonewu.agenteam.model.plugin.entity.PluginToolSource;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.tool.ToolSchemaValidation;
import org.springframework.stereotype.Service;

/**
 * 发布时固定实际安装编号，用户配置不能替换工具结构和程序包摘要。
 */
@Service
public class JavaPluginSources {
    private final JavaPluginRegistry registry;
    private final ToolSchemaValidation schemas;
    private final ResourceJson json;

    public JavaPluginSources(JavaPluginRegistry registry, ToolSchemaValidation schemas, ResourceJson json) {
        this.registry = registry;
        this.schemas = schemas;
        this.json = json;
    }

    public JavaPluginInstallationRow require(AuthContext actor, JsonNode source) {
        var row = registry.require(actor.enterpriseId(), source.path("versionId").asText());
        registry.matchPlugin(row, source.path("id").asText());
        var configuration = source.path("configuration");
        if (!configuration.isObject()) {
            throw ApiException.invalidField("config.sources", "请填写插件配置。");
        }
        try {
            schemas.arguments(json.read(row.getConfigurationSchema()), configuration);
        } catch (ApiException failure) {
            throw ApiException.invalidField("config.sources", "插件配置不符合要求，请核对必填项及字段格式。", failure);
        }
        return row;
    }

    public PluginToolSources.Resolved resolve(AuthContext actor, JsonNode source, JsonNode selection, int timeout) {
        var row = require(actor, source);
        var tool = registry.tools(row).stream().filter(value -> value.name().equals(selection.path("name").asText()))
            .findFirst().orElseThrow(() -> ApiException.invalidField("config.tools", "所选工具不在此插件版本中。"));
        ObjectNode config = source.deepCopy();
        config.put("pluginType", "java").put("timeoutSeconds", timeout);
        var fixed = new PluginToolSource("java", row.getPluginId(), row.getId(), row.getPluginVersion(), row.getName(), config);
        return new PluginToolSources.Resolved(selection.path("id").asText(), tool, fixed);
    }
}
