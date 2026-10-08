package com.stonewu.agenteam.service.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 副本只保留仍允许引用的固定依赖，移除内容同时形成明确的待修正字段。
 */
@Component
public class ResourceCopyConfiguration {
    private final ResourceConfigurationService configurations;
    private final FixedDependencyService dependencies;
    private final CredentialBindingService credentials;

    public ResourceCopyConfiguration(ResourceConfigurationService configurations, FixedDependencyService dependencies,
                                     CredentialBindingService credentials) {
        this.configurations = configurations;
        this.dependencies = dependencies;
        this.credentials = credentials;
    }

    public record Result(JsonNode config, Map<String, List<String>> fieldErrors) {
    }

    public Result copy(AuthContext actor, ResourceRecord resource) {
        ObjectNode config = resource.config().deepCopy();
        Map<String, List<String>> errors = new LinkedHashMap<>();
        for (var binding : configurations.dependencies(resource.kind(), resource.config())) {
            try {
                dependencies.require(actor, binding);
            } catch (ResponseStatusException unavailable) {
                if (!List.of(403, 404, 409).contains(unavailable.getStatusCode().value())) {
                    throw unavailable;
                }
                remove(config, binding);
                errors.put("config." + binding.bindingKey(),
                    List.of("原依赖已失效或无法使用，请重新选择或确认移除后保存草稿。"));
            }
        }
        if (config.hasNonNull("credentialId") && !credentials.canBind(actor)) {
            config.putNull("credentialId");
            errors.put("config.credentialId", List.of("副本未保留连接凭据，请重新选择或确认不使用凭据后保存。"));
        }
        if (resource.kind() == ResourceKind.PLUGIN && PluginConfigurationMapper.modern(config) && !credentials.canBind(
            actor)) {
            for (var source : config.path("sources")) {
                if (source.hasNonNull("credentialId")) {
                    ((ObjectNode) source).putNull("credentialId");
                    errors.put("config.sources", List.of("副本未保留连接凭据，请重新选择或确认不使用凭据后保存。"));
                }
            }
        }
        return new Result(config, Map.copyOf(errors));
    }

    private void remove(ObjectNode config, DependencyBinding binding) {
        if (binding.bindingKey().startsWith("sources/")) {
            String id = binding.bindingKey().substring("sources/".length());
            var sources = new ArrayList<JsonNode>();
            var tools = new ArrayList<JsonNode>();
            config.path("sources").forEach(source -> {
                if (!source.path("id").asText().equals(id)) {
                    sources.add(source);
                }
            });
            config.path("tools").forEach(tool -> {
                if (!tool.path("sourceId").asText().equals(id)) {
                    tools.add(tool);
                }
            });
            config.putArray("sources").addAll(sources);
            config.putArray("tools").addAll(tools);
        } else if (binding.bindingKey().startsWith("nodes/")) {
            String[] parts = binding.bindingKey().split("/");
            for (JsonNode node : config.path("nodes")) {
                if (node.path("nodeId").asText().equals(parts[1])) {
                    ((ObjectNode) node.path("config")).putNull(parts[3]);
                }
            }
        } else if (config.path(binding.bindingKey()).isArray()) {
            // 使用来源位置中的版本过滤，数组被前一次移除后序号可能已经改变。
            var remaining = new ArrayList<JsonNode>();
            for (JsonNode value : config.path(binding.bindingKey())) {
                if (!value.asText().equals(binding.versionId())) {
                    remaining.add(value);
                }
            }
            config.putArray(binding.bindingKey()).addAll(remaining);
        } else {
            config.putNull(binding.bindingKey());
        }
    }
}
