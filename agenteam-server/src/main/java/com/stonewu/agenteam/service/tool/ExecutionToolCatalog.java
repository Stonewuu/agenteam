package com.stonewu.agenteam.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.execution.entity.RunRecord;
import com.stonewu.agenteam.model.plugin.entity.PluginToolRecord;
import com.stonewu.agenteam.model.tool.entity.ExecutionToolBinding;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.workspace.WorkspaceToolDefinitions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 只读取员工明确绑定的固定版本，技能不能借助间接依赖扩大工具范围。
 */
@Service
public class ExecutionToolCatalog {
    private final PluginToolMapper tools;
    private final SourceToolDefinitions sources;
    private final WorkspaceToolDefinitions workspace;
    private final ResourceVersionMapper versions;

    public ExecutionToolCatalog(PluginToolMapper tools, SourceToolDefinitions sources,
                                WorkspaceToolDefinitions workspace, ResourceVersionMapper versions) {
        this.tools = tools;
        this.sources = sources;
        this.workspace = workspace;
        this.versions = versions;
    }

    public Map<String, ExecutionToolBinding> list(RunRecord run) {
        return list(run, run.executionConfig().path("config"));
    }

    public Map<String, ExecutionToolBinding> list(RunRecord run, JsonNode config) {
        return list(run, config, availableVersions(run));
    }

    private Map<String, ExecutionToolBinding> list(RunRecord run, JsonNode config, Set<String> available) {
        Map<String, ExecutionToolBinding> result = new LinkedHashMap<>();
        Map<String, List<PluginToolRecord>> sourceTools = new LinkedHashMap<>();
        for (var version : config.path("pluginVersionIds")) {
            if (!available.contains(version.asText())) {
                continue;
            }
            var fixed = run.executionConfig().path("dependencies");
            for (var dependency : fixed) {
                if (version.asText().equals(dependency.path("versionId").asText()) && dependency.path("kind").asText()
                    .equals("plugin")) {
                    for (var tool : tools.list(run.enterpriseId(), version.asText())) {
                        if (tool.enabled()) {
                            var binding = binding(dependency, tool);
                            boolean platform = binding.config().path("pluginType").asText().equals("builtin")
                                && Set.of("todo_management", "schedule_management")
                                .contains(binding.config().path("builtinCode").asText());
                            if (platform && !run.mode().equals("interactive") && !tool.definition().operationClass()
                                .equals("read")) {
                                continue;
                            }
                            if (!sourceEnabled(run, tool, sourceTools, available)) {
                                continue;
                            }
                            result.put(binding.alias(), binding);
                        }
                    }
                }
            }
        }
        for (String kind : List.of("knowledge", "data")) {
            Set<String> configured = new HashSet<>();
            config.path(kind + "VersionIds").forEach(value -> configured.add(value.asText()));
            for (var dependency : run.executionConfig().path("dependencies")) {
                if (kind.equals(dependency.path("kind").asText()) && configured.contains(
                    dependency.path("versionId").asText())
                    && available.contains(dependency.path("versionId").asText())) {
                    for (var definition : sources.forKind(kind)) {
                        var binding = new ExecutionToolBinding(dependency.path("resourceId").asText(),
                            dependency.path("versionId").asText(), kind,
                            dependency.path("name").asText(), null, definition, dependency.path("config"));
                        result.put(binding.alias(), binding);
                    }
                }
            }
        }
        if (result.values().stream().map(ExecutionToolBinding::identity).distinct().count() > 100) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                "本次任务可用工具超过允许数量。");
        }
        result.putAll(workspace.list(run));
        return Map.copyOf(result);
    }

    /**
     * 仅供调用事务和确认校验使用；不会把其他流程节点的工具加入当前智能体。
     */
    public Map<String, ExecutionToolBinding> all(RunRecord run) {
        var available = availableVersions(run);
        Map<String, ExecutionToolBinding> result = new LinkedHashMap<>(
            list(run, run.executionConfig().path("config"), available));
        workspace.delegatedResults(run).values().forEach(binding -> result.put(binding.alias(), binding));
        var workflows = new ArrayList<JsonNode>();
        if (run.executionConfig().has("workflowId")) {
            workflows.add(run.executionConfig().path("config"));
        }
        for (var dependency : run.executionConfig().path("dependencies")) {
            if (!available.contains(dependency.path("versionId").asText())) {
                continue;
            }
            if (dependency.path("kind").asText().equals("agent")) {
                result.putAll(list(run, dependency.path("config"), available));
            }
            if (dependency.path("kind").asText().equals("workflow")) {
                workflows.add(dependency.path("config"));
            }
        }
        for (var workflow : workflows) {
            for (var node : workflow.path("nodes")) {
                if (!node.path("type").asText().equals("tool")) {
                    continue;
                }
                var binding = tool(run, node.path("config").path("pluginVersionId").asText(),
                    selectionKey(node.path("config")), available);
                result.put(binding.alias(), binding);
            }
        }
        return Map.copyOf(result);
    }

    public ExecutionToolBinding tool(RunRecord run, String versionId, String name) {
        return tool(run, versionId, name, availableVersions(run));
    }

    private ExecutionToolBinding tool(RunRecord run, String versionId, String name, Set<String> available) {
        Map<String, List<PluginToolRecord>> sourceTools = new LinkedHashMap<>();
        for (var dependency : run.executionConfig().path("dependencies")) {
            if (!available.contains(versionId) || !dependency.path("versionId").asText()
                .equals(versionId) || !dependency.path("kind").asText().equals("plugin")) {
                continue;
            }
            var matches = tools.list(run.enterpriseId(), versionId).stream()
                .filter(tool -> tool.enabled() && sourceEnabled(run, tool, sourceTools, available))
                .filter(
                    tool -> tool.id().equals(name) || name.equals(tool.entryId()) || !PluginConfigurationMapper.modern(
                        dependency.path("config")) && tool.definition().name().equals(name)).toList();
            if (matches.size() == 1) {
                return binding(dependency, matches.getFirst());
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
            "本次流程节点使用的工具已不可用。");
    }

    public ExecutionToolBinding requireCurrent(RunRecord run, ExecutionToolBinding expected) {
        var current = all(run).get(expected.alias());
        if (current == null || !current.definition().schemaHash().equals(expected.definition().schemaHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_DEPENDENCY_UNAVAILABLE",
                "本次任务使用的工具已不可用。");
        }
        return current;
    }

    public static String selectionKey(JsonNode config) {
        return config.hasNonNull("toolId") ? config.path("toolId").asText() : config.path("toolName").asText();
    }

    public static ExecutionToolBinding binding(JsonNode dependency, PluginToolRecord tool) {
        return new ExecutionToolBinding(dependency.path("resourceId").asText(), dependency.path("versionId").asText(),
            "plugin", dependency.path("name").asText(),
            tool.id(), tool.definition(), tool.source() == null ? dependency.path("config") : tool.source().config(),
            tool.entryId(), tool.source());
    }

    private Set<String> availableVersions(RunRecord run) {
        List<String> ids = new ArrayList<>();
        for (var dependency : run.executionConfig().path("dependencies")) {
            ids.add(dependency.path("versionId").asText());
        }
        return versions.available(run.enterpriseId(), ids.stream().distinct().toList());
    }

    private boolean sourceEnabled(RunRecord run, PluginToolRecord tool, Map<String, List<PluginToolRecord>> sourceTools,
                                  Set<String> available) {
        if (tool.source() == null || !tool.source().type().equals("builtin")) {
            return true;
        }
        if (!available.contains(tool.source().versionId())) {
            return false;
        }
        return sourceTools.computeIfAbsent(tool.source().versionId(), id -> tools.list(run.enterpriseId(), id)).stream()
            .anyMatch(source -> source.enabled()
                && source.definition().name().equals(tool.definition().name()) && source.definition().schemaHash()
                .equals(tool.definition().schemaHash()));
    }
}
