package com.stonewu.agenteam.service.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.DependencyBinding;
import com.stonewu.agenteam.model.resource.entity.ResolvedDependency;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.entity.ResourceRecord;
import com.stonewu.agenteam.model.tool.entity.ToolInvocationIdentity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.workflow.WorkflowNodeDependencies;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 固定直接及间接依赖，每次使用仍检查当前成员、授权、版本和停用状态。
 */
@Service
public class FixedDependencyService {
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final ResourceConfigurationService configurations;
    private final WorkflowNodeDependencies workflowNodes;
    private final PluginToolMapper pluginTools;

    public FixedDependencyService(ResourceMapper resources, ResourceVersionMapper versions,
                                  ResourceAuthorizationService access, ResourceConfigurationService configurations,
                                  WorkflowNodeDependencies workflowNodes, PluginToolMapper pluginTools) {
        this.resources = resources;
        this.versions = versions;
        this.access = access;
        this.configurations = configurations;
        this.workflowNodes = workflowNodes;
        this.pluginTools = pluginTools;
    }

    public List<DependencyBinding> resolve(AuthContext actor, ResourceRecord root) {
        return resolveGraph(actor, root).direct();
    }

    /**
     * 执行配置复用已经验证的全部固定依赖，避免再次逐个读取。
     */
    public record ResolvedGraph(List<DependencyBinding> direct, List<ResolvedDependency> resources) {
    }

    public ResolvedGraph resolveGraph(AuthContext actor, ResourceRecord root) {
        return resolveGraph(actor, root, null);
    }

    /**
     * 执行配置已经逐个验证权限，只从准备好的配置继续检查依赖关系。
     */
    ResolvedGraph resolveGraph(AuthContext actor, ResourceRecord root, Map<String, ResolvedDependency> prepared) {
        var direct = configurations.dependencies(root.kind(), root.config());
        Map<String, ResolvedDependency> loaded = new HashMap<>();
        Map<String, Subgraph> completed = new HashMap<>();
        Set<String> path = new HashSet<>();
        path.add(root.id());
        for (var binding : direct) {
            walk(actor, binding, path, loaded, completed, 1, root.kind() == ResourceKind.WORKFLOW ? 1 : 0, prepared);
        }
        if (root.kind() == ResourceKind.AGENT) {
            validateAgentSkills(root.config(), loaded);
        }
        if (root.kind() == ResourceKind.AGENT) {
            validateSubagents(root.config(), loaded);
        }
        for (var dependency : loaded.values()) {
            if (dependency.resource().kind() == ResourceKind.AGENT) {
                validateAgentSkills(dependency.version().config(), loaded);
                validateSubagents(dependency.version().config(), loaded);
            }
        }
        if (root.kind() == ResourceKind.WORKFLOW) {
            workflowNodes.validate(actor.enterpriseId(), root.config(), loaded);
        }
        for (var dependency : loaded.values()) {
            if (dependency.resource().kind() == ResourceKind.WORKFLOW) {
                workflowNodes.validate(actor.enterpriseId(), dependency.version().config(), loaded);
            }
        }
        Set<ToolInvocationIdentity> tools = new HashSet<>();
        Set<String> exposedPlugins = new HashSet<>();
        collectPluginVersions(root.kind(), root.config(), exposedPlugins);
        for (var dependency : loaded.values()) {
            collectPluginVersions(dependency.resource().kind(), dependency.version().config(), exposedPlugins);
        }
        for (String version : exposedPlugins) {
            var dependency = loaded.get(version);
            if (dependency == null) {
                continue;
            }
            pluginTools.list(actor.enterpriseId(), version).stream().filter(tool -> tool.enabled())
                .forEach(tool -> tools.add(ToolInvocationIdentity.of(dependency.version(), tool)));
        }
        if (tools.size() > 100) {
            throw ApiException.invalidField("config.pluginVersionIds", "所有依赖提供的工具去重后不能超过一百个。");
        }
        return new ResolvedGraph(direct, loaded.values().stream()
            .sorted((left, right) -> left.version().id().compareTo(right.version().id())).toList());
    }

    public ResolvedDependency require(AuthContext actor, DependencyBinding binding) {
        var version = versions.find(actor.enterpriseId(), binding.versionId())
            .filter(item -> item.status().equals("available")).orElseThrow(this::unavailable);
        var resource = resources.find(actor.enterpriseId(), version.resourceId(), false, false)
            .orElseThrow(this::unavailable);
        if (!resource.kind().code().equals(binding.kind())) {
            throw unavailable();
        }
        access.requireUse(actor, resource.id(), binding.kind());
        configurations.use(actor, resource.kind(), version.config());
        return new ResolvedDependency(resource, version);
    }

    private record Subgraph(int height, int workflows, Set<String> resources) {
    }

    private Subgraph walk(AuthContext actor, DependencyBinding binding, Set<String> path,
                          Map<String, ResolvedDependency> loaded,
                          Map<String, Subgraph> completed, int depth, int workflows,
                          Map<String, ResolvedDependency> prepared) {
        if (depth > 8) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_DEPENDENCY_DEPTH",
                "资源依赖最多八层，请减少嵌套引用。");
        }
        var dependency = loaded.computeIfAbsent(binding.versionId(),
            id -> prepared == null ? require(actor, binding) : prepared.get(id));
        if (dependency == null) {
            throw unavailable();
        }
        if (!dependency.resource().kind().code().equals(binding.kind())) {
            throw unavailable();
        }
        String resourceId = dependency.resource().id();
        var previous = completed.get(dependency.version().id());
        if (previous != null) {
            if (previous.resources().stream().anyMatch(path::contains)) {
                cycle();
            }
            if (depth + previous.height() - 1 > 8) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_DEPENDENCY_DEPTH",
                    "资源依赖最多八层，请减少嵌套引用。");
            }
            if (workflows + previous.workflows() > 1) {
                throw ApiException.invalidField("config", "工作流不能直接或通过智能体嵌套另一个工作流。");
            }
            return previous;
        }
        if (!path.add(resourceId)) {
            cycle();
        }
        int currentWorkflow = dependency.resource().kind() == ResourceKind.WORKFLOW ? 1 : 0;
        int nestedWorkflows = workflows + currentWorkflow;
        if (nestedWorkflows > 1) {
            throw ApiException.invalidField("config", "工作流不能直接或通过智能体嵌套另一个工作流。");
        }
        int height = 1;
        int workflowDepth = currentWorkflow;
        Set<String> descendants = new HashSet<>();
        descendants.add(resourceId);
        var children = prepared == null ? versions.dependencies(actor.enterpriseId(), dependency.version().id())
            : configurations.dependencies(dependency.resource().kind(), dependency.version().config());
        for (var child : children) {
            var subtree = walk(actor, child, path, loaded, completed, depth + 1, nestedWorkflows, prepared);
            height = Math.max(height, 1 + subtree.height());
            workflowDepth = Math.max(workflowDepth, currentWorkflow + subtree.workflows());
            descendants.addAll(subtree.resources());
        }
        path.remove(resourceId);
        var result = new Subgraph(height, workflowDepth, Set.copyOf(descendants));
        completed.put(dependency.version().id(), result);
        return result;
    }

    private void validateAgentSkills(JsonNode agent, Map<String, ResolvedDependency> dependencies) {
        Set<String> pluginIds = ids(agent.path("pluginVersionIds"));
        Set<String> knowledgeIds = ids(agent.path("knowledgeVersionIds"));
        for (JsonNode skillId : agent.path("skillVersionIds")) {
            var skill = dependencies.get(skillId.asText());
            if (skill == null || !pluginIds.containsAll(ids(skill.version().config().path("pluginVersionIds")))
                || !knowledgeIds.containsAll(ids(skill.version().config().path("knowledgeVersionIds")))) {
                throw new ApiException(HttpStatus.CONFLICT, "SKILL_DEPENDENCY_UNAVAILABLE",
                    "所选技能需要的插件或知识库尚未包含在智能体能力中，请补齐后发布。");
            }
        }
    }

    private void validateSubagents(JsonNode config, Map<String, ResolvedDependency> dependencies) {
        for (var id : config.path("subagentVersionIds")) {
            var child = dependencies.get(id.asText());
            if (child == null || child.resource().kind() != ResourceKind.AGENT) {
                throw unavailable();
            }
            var settings = child.version().config();
            if (!Set.of("chat", "task").contains(settings.path("agentType").asText()) || !settings.path(
                "workflowVersionIds").isEmpty()) {
                throw ApiException.invalidField("config.subagentVersionIds",
                    "子智能体请选择不含工作流能力的对话型或任务型智能体。");
            }
        }
    }

    private Set<String> ids(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return result;
    }

    private void collectPluginVersions(ResourceKind kind, JsonNode config, Set<String> values) {
        if (kind == ResourceKind.AGENT || kind == ResourceKind.SKILL) {
            config.path("pluginVersionIds").forEach(id -> values.add(id.asText()));
        }
        if (kind == ResourceKind.WORKFLOW) {
            for (var node : config.path("nodes")) {
                if (node.path("type").asText().equals("tool")) {
                    values.add(node.path("config").path("pluginVersionId").asText());
                }
            }
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
            "引用的资源版本已失效或无法使用，请重新选择。");
    }

    private void cycle() {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_DEPENDENCY_CYCLE",
            "资源之间存在循环引用，请调整依赖后发布。");
    }
}
