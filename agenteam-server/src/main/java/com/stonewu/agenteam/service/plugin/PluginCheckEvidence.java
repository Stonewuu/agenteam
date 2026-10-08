package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 清单可供草稿继续选择，发布资格仍与完整配置摘要及检查时间共同验证。
 */
@Component
public class PluginCheckEvidence {
    private final ObjectMapper mapper;
    private final ResourceJson json;

    public PluginCheckEvidence(ObjectMapper mapper, ResourceJson json) {
        this.mapper = mapper;
        this.json = json;
    }

    public String connectionHash(JsonNode config) {
        config = PluginConfigurationMapper.connection(config);
        var fields = new LinkedHashMap<String, JsonNode>();
        for (String key : List.of("pluginType", "builtinCode", "transport", "endpoint", "credentialId",
            "timeoutSeconds")) {
            fields.put(key, config.path(key));
        }
        return json.hash(json.tree(fields));
    }

    public List<ToolDefinition> tools(ResourceRecord resource) {
        JsonNode validation = resource.validation();
        if (validation == null || !connectionHash(resource.config()).equals(
            validation.path("connection").path("connectionHash").asText())
            || !validation.path("connection").path("tools").isArray()) {
            return List.of();
        }
        return mapper.convertValue(validation.path("connection").path("tools"), new TypeReference<>() {
        });
    }

    public JsonNode retained(ResourceRecord resource, JsonNode newConfig) {
        if (resource.validation() == null || !connectionHash(resource.config()).equals(connectionHash(newConfig))) {
            return null;
        }
        var retained = resource.validation().deepCopy();
        // 保存草稿代表维护者已检查选择，保留清单供再次检查比较，但不保留旧错误结论。
        if (retained.isObject()) {
            ((ObjectNode) retained).remove("fieldErrors");
        }
        return retained;
    }

}
