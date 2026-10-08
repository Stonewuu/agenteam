package com.stonewu.agenteam.service.resource;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.workflow.WorkflowToolSelectionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.resource.validation.ResourceConfigValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用结构验证后委派给对应类型，公开响应不携带内部的 JSON 库对象。
 */
@Service
public class ResourceConfigurationService {
    private final ResourceJson json;
    private final ObjectMapper mapper;
    private final PluginConfigurationMapper pluginConfigs;
    private final WorkflowToolSelectionMapper workflowTools;
    private final Map<ResourceKind, ResourceConfigValidator> validators = new EnumMap<>(ResourceKind.class);

    public ResourceConfigurationService(ResourceJson json, ObjectMapper mapper,
                                        List<ResourceConfigValidator> validators,
                                        PluginConfigurationMapper pluginConfigs,
                                        WorkflowToolSelectionMapper workflowTools) {
        this.json = json;
        this.mapper = mapper;
        this.pluginConfigs = pluginConfigs;
        this.workflowTools = workflowTools;
        for (var validator : validators) {
            if (this.validators.put(validator.kind(), validator) != null) {
                throw new IllegalStateException("资源类型重复注册校验器");
            }
        }
        if (this.validators.size() != ResourceKind.values().length) {
            throw new IllegalStateException("存在尚未配置校验器的资源类型");
        }
    }

    public JsonNode draft(ResourceKind kind, Object value) {
        JsonNode config = json.checkedSize(json.tree(value));
        if (kind == ResourceKind.AGENT && config instanceof ObjectNode object) {
            object.remove(List.of("researchSubagentEnabled", "subagents"));
        }
        validators.get(kind).draft(config);
        return config;
    }

    public JsonNode draft(AuthContext actor, ResourceKind kind, Object value) {
        JsonNode config = draft(kind, value);
        if (kind == ResourceKind.PLUGIN) {
            config = pluginConfigs.normalize(actor.enterpriseId(), config);
            validators.get(kind).draft(config);
        }
        if (kind == ResourceKind.WORKFLOW) {
            config = workflowTools.normalize(actor, config);
        }
        return config;
    }

    public void publish(AuthContext actor, ResourceRecord resource) {
        var pending = savedErrors(resource);
        if (!pending.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请先处理副本中待修正的依赖并保存草稿。", Map.of(), pending);
        }
        validators.get(resource.kind()).validatePublished(resource.config());
        if ((resource.kind() == ResourceKind.AGENT || resource.kind() == ResourceKind.PLUGIN) && resource.description()
            .isBlank()) {
            throw ApiException.invalidField("description", "发布前请填写简介。");
        }
        validators.get(resource.kind()).publish(actor, resource);
    }

    public void use(AuthContext actor, ResourceKind kind, JsonNode config) {
        validators.get(kind).validatePublished(config);
        validators.get(kind).use(actor, config);
    }

    public List<DependencyBinding> dependencies(ResourceKind kind, JsonNode config) {
        return validators.get(kind).dependencies(config);
    }

    public String subtype(ResourceKind kind, JsonNode config) {
        return switch (kind) {
            case AGENT -> config.path("agentType").asText();
            case PLUGIN -> PluginConfigurationMapper.mode(config);
            case DATA -> config.path("sourceType").asText();
            default -> null;
        };
    }

    public Map<String, List<String>> errors(ResourceRecord resource) {
        var result = new LinkedHashMap<>(savedErrors(resource));
        try {
            validators.get(resource.kind()).validatePublished(resource.config());
        } catch (ApiException invalid) {
            result.putAll(invalid.fieldErrors());
        }
        return Map.copyOf(result);
    }

    private Map<String, List<String>> savedErrors(ResourceRecord resource) {
        if (resource.validation() == null || !resource.configHash()
            .equals(resource.validation().path("configHash").asText()) || !resource.validation().has("fieldErrors")) {
            return Map.of();
        }
        return mapper.convertValue(resource.validation().get("fieldErrors"), new TypeReference<>() {
        });
    }
}
