package com.stonewu.agenteam.service.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.*;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.FixedDependencyService.ResolvedGraph;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 只调整本次执行的配置，已发布的原文和依赖关系保持不变。
 */
@Service
public class ExecutionDependencyService {
    private static final Set<String> OPTIONAL_AGENT_FIELDS = Set.of("pluginVersionIds", "skillVersionIds",
        "knowledgeVersionIds", "dataVersionIds", "workflowVersionIds", "subagentVersionIds");
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final ResourceConfigurationService configurations;
    private final FixedDependencyService dependencies;
    private final ResourceJson json;

    public ExecutionDependencyService(ResourceMapper resources, ResourceVersionMapper versions,
                                      ResourceAuthorizationService access, ResourceConfigurationService configurations,
                                      FixedDependencyService dependencies, ResourceJson json) {
        this.resources = resources;
        this.versions = versions;
        this.access = access;
        this.configurations = configurations;
        this.dependencies = dependencies;
        this.json = json;
    }

    public record Prepared(ResourceRecord resource, ResolvedGraph dependencies) {
    }

    public Prepared prepare(AuthContext actor, ResourceRecord root) {
        return prepare(actor, root, true);
    }

    /**
     * 读取输入候选时无需先选定顶层模型，依赖的权限和版本仍然逐项检查。
     */
    public Prepared prepareInputOptions(AuthContext actor, ResourceRecord root) {
        return prepare(actor, root, false);
    }

    private Prepared prepare(AuthContext actor, ResourceRecord root, boolean validateRootModel) {
        var state = new Preparation(actor, validateRootModel);
        Set<String> path = new HashSet<>();
        path.add(root.id());
        var config = state.configuration(root.kind(), root.config(), path, 0);
        if (config == null) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                "此流程所需的资源已删除，请替换不可用的资源后重新发布。");
        }
        var prepared = root.withConfiguration(config, json.hash(config));
        return new Prepared(prepared, dependencies.resolveGraph(actor, prepared, state.prepared));
    }

    private final class Preparation {
        private final AuthContext actor;
        private final boolean validateRootModel;
        private final Map<String, ResourceVersionRecord> loadedVersions = new HashMap<>();
        private final Map<String, ResourceRecord> loadedResources = new HashMap<>();
        private final Map<String, ResolvedDependency> prepared = new HashMap<>();
        private final Set<String> unavailableVersions = new HashSet<>();
        private final Set<String> authorizedResources = new HashSet<>();

        private Preparation(AuthContext actor, boolean validateRootModel) {
            this.actor = actor;
            this.validateRootModel = validateRootModel;
        }

        private ObjectNode configuration(ResourceKind kind, JsonNode original, Set<String> path, int depth) {
            var config = (ObjectNode) configurations.draft(kind, original);
            var bindings = configurations.dependencies(kind, config);
            load(bindings);
            boolean removedSource = false;
            for (var binding : bindings) {
                if (resolve(binding, path, depth + 1) != null) {
                    continue;
                }
                if (kind == ResourceKind.AGENT && OPTIONAL_AGENT_FIELDS.contains(binding.bindingKey())) {
                    removeVersion(config, binding.bindingKey(), binding.versionId());
                } else if (kind == ResourceKind.PLUGIN && binding.bindingKey().startsWith("sources/")) {
                    removeSource(config, binding.bindingKey().substring("sources/".length()));
                    removedSource = true;
                } else {
                    // 技能和工作流的依赖不可缺省；入口工作流也不能被当作可选能力移除。
                    return null;
                }
            }
            if (removedSource && config.path("tools").isEmpty()) {
                return null;
            }
            if (depth > 0 || validateRootModel || kind != ResourceKind.AGENT) {
                configurations.use(actor, kind, config);
            }
            return config;
        }

        private ResolvedDependency resolve(DependencyBinding binding, Set<String> path, int depth) {
            if (depth > 8) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_DEPENDENCY_DEPTH",
                    "资源依赖最多八层，请减少嵌套引用。");
            }
            var version = loadedVersions.get(binding.versionId());
            var resource = version == null ? null : loadedResources.get(version.resourceId());
            if (resource == null || !resource.kind().code().equals(binding.kind())) {
                throw unavailable();
            }
            if (resource.deletedAt() != null || resource.status().equals("deleted")) {
                unavailableVersions.add(version.id());
                return null;
            }
            if (!version.status().equals("available")) {
                throw unavailable();
            }
            if (path.contains(resource.id())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_DEPENDENCY_CYCLE",
                    "资源之间存在循环引用，请调整依赖后发布。");
            }
            if (unavailableVersions.contains(version.id())) {
                return null;
            }
            if (prepared.containsKey(version.id())) {
                return prepared.get(version.id());
            }
            if (authorizedResources.add(resource.id())) {
                access.requireUse(actor, resource.id(), binding.kind());
            }
            path.add(resource.id());
            ObjectNode config;
            try {
                config = configuration(resource.kind(), version.config(), path, depth);
            } finally {
                path.remove(resource.id());
            }
            if (config == null) {
                unavailableVersions.add(version.id());
                return null;
            }
            // 此副本只进入执行配置，不回写发布版本。
            var effectiveVersion = new ResourceVersionRecord(version.id(), version.enterpriseId(), version.resourceId(),
                version.versionNo(), version.name(), version.description(), config, json.hash(config),
                version.releaseNote(),
                version.status(), version.publishedBy(), version.publishedByName(), version.publishedAt());
            var result = new ResolvedDependency(resource, effectiveVersion);
            prepared.put(version.id(), result);
            return result;
        }

        private void load(List<DependencyBinding> bindings) {
            var ids = bindings.stream().map(DependencyBinding::versionId).distinct()
                .filter(id -> !loadedVersions.containsKey(id)).toList();
            loadedVersions.putAll(versions.findMany(actor.enterpriseId(), ids));
            var resourceIds = ids.stream().map(loadedVersions::get).filter(value -> value != null)
                .map(ResourceVersionRecord::resourceId).distinct().filter(id -> !loadedResources.containsKey(id))
                .toList();
            loadedResources.putAll(resources.findMany(actor.enterpriseId(), resourceIds, true));
        }
    }

    private void removeVersion(ObjectNode config, String field, String id) {
        List<JsonNode> retained = new ArrayList<>();
        for (var value : config.path(field)) {
            if (!value.asText().equals(id)) {
                retained.add(value);
            }
        }
        config.putArray(field).addAll(retained);
    }

    private void removeSource(ObjectNode config, String id) {
        for (String field : List.of("sources", "tools")) {
            String key = field.equals("sources") ? "id" : "sourceId";
            List<JsonNode> retained = new ArrayList<>();
            for (var value : config.path(field)) {
                if (!value.path(key).asText().equals(id)) {
                    retained.add(value);
                }
            }
            config.putArray(field).addAll(retained);
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
            "引用的资源版本已失效或无法使用，请重新选择。");
    }
}
