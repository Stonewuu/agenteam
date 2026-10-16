package com.stonewu.agenteam.service.workflow;

import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolViewMapper;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.UsableVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import com.stonewu.agenteam.model.workflow.response.WorkflowDependencyOptions;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 工作流作者可读取有权使用的固定能力，不能借编辑节点读取整个资源配置。
 */
@Service
public class WorkflowDependencyOptionsService {
    private final EnterpriseAuthorizationService authorization;
    private final ResourceAuthorizationService access;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final UsableVersionMapper usable;
    private final PluginToolMapper tools;
    private final PluginToolViewMapper views;

    public WorkflowDependencyOptionsService(EnterpriseAuthorizationService authorization,
                                            ResourceAuthorizationService access, ResourceMapper resources,
                                            ResourceVersionMapper versions, UsableVersionMapper usable,
                                            PluginToolMapper tools, PluginToolViewMapper views) {
        this.authorization = authorization;
        this.access = access;
        this.resources = resources;
        this.versions = versions;
        this.usable = usable;
        this.tools = tools;
        this.views = views;
    }

    public WorkflowDependencyOptions options(AuthContext actor, String versionId) {
        authorization.require(actor, "capabilities.view");
        if (Set.of("workflow.create", "workflow.edit", "workflow.preview").stream()
            .noneMatch(actor.permissions()::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有配置工作流的权限。");
        }
        var version = versions.find(actor.enterpriseId(), versionId).filter(row -> row.status().equals("available"))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        var resource = resources.find(actor.enterpriseId(), version.resourceId(), false, false)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        if (!Set.of(ResourceKind.AGENT, ResourceKind.PLUGIN).contains(resource.kind())) {
            throw ResourceAuthorizationService.unavailable();
        }
        access.requireUse(actor, resource.id(), resource.kind().code());
        var config = version.config();
        if (resource.kind() == ResourceKind.AGENT) {
            List<String> ids = new ArrayList<>();
            config.path("skillVersionIds").forEach(value -> ids.add(value.asText()));
            var skills = ids.isEmpty() || !actor.permissions().contains("skill.use") ? List.<UsableVersionView>of()
                : usable.selected(access.usageScope(actor, "skill"), ids);
            return new WorkflowDependencyOptions("agent", config.path("agentType").asText(), skills, List.of(), Map.of());
        }
        var available = tools.list(actor.enterpriseId(), versionId).stream().filter(tool -> tool.enabled()).toList();
        Map<String, String> roots = new LinkedHashMap<>();
        for (var tool : available) {
            String provider = tool.source() == null ? config.path("pluginType").asText() : tool.source().type();
            // MCP 返回的结构化数据位于 structuredContent，其他提供方直接返回工具声明的结果。
            roots.put(tool.entryId() == null ? tool.id() : tool.entryId(), provider.equals("mcp") ? "structuredContent" : "");
        }
        return new WorkflowDependencyOptions("plugin", null, List.of(),
            available.stream().map(tool -> views.map(tool, config)).toList(), roots);
    }
}
