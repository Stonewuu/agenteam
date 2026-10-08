package com.stonewu.agenteam.service.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonewu.agenteam.mapper.audit.AuditEventMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.plugin.BuiltinPluginResourceMapper;
import com.stonewu.agenteam.mapper.plugin.PluginToolMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.permission.entity.ResourceGrantSpec;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平台登记的插件具有真实发布版本，可直接被配置引用，不要求使用者先创建插件草稿。
 */
@Service
public class BuiltinPluginResourceService {
    private static final Logger LOG = LoggerFactory.getLogger(BuiltinPluginResourceService.class);
    private final EnterpriseMapper enterprises;
    private final BuiltinPluginResourceMapper selection;
    private final BuiltinPluginRegistry registry;
    private final ResourceMapper resources;
    private final ResourceVersionMapper versions;
    private final PluginToolMapper tools;
    private final ResourceAuthorizationMapper grants;
    private final ResourceJson json;
    private final AuditEventMapper audit;
    private final Clock clock;

    public BuiltinPluginResourceService(EnterpriseMapper enterprises, BuiltinPluginResourceMapper selection,
                                        BuiltinPluginRegistry registry,
                                        ResourceMapper resources, ResourceVersionMapper versions,
                                        PluginToolMapper tools, ResourceAuthorizationMapper grants, ResourceJson json,
                                        AuditEventMapper audit, Clock clock) {
        this.enterprises = enterprises;
        this.selection = selection;
        this.registry = registry;
        this.resources = resources;
        this.versions = versions;
        this.tools = tools;
        this.grants = grants;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void initialize(String enterprise) {
        if (enterprises.lockEnterprise(enterprise).isEmpty()) {
            return;
        }
        var administrator = selection.administrator(enterprise);
        if (administrator.isEmpty()) {
            LOG.warn("企业 {} 暂无有效管理员，内置插件将在下次初始化时补充", enterprise);
            return;
        }
        for (var entry : registry.list()) {
            initialize(enterprise, administrator.get(), registry.require(entry.code()));
        }
    }

    private void initialize(String enterprise, String owner, BuiltinPluginAdapter adapter) {
        String resourceId = stableId("builtin-plugin:" + enterprise + ":" + adapter.code());
        var config = configuration(adapter);
        String descriptor = json.hash(json.tree(
            Map.of("name", adapter.name(), "description", adapter.description(), "config", config, "tools",
                adapter.tools())));
        String versionId = stableId(resourceId + ":" + descriptor);
        var resource = resources.find(enterprise, resourceId, true, true).orElse(null);
        if (resource != null) {
            if (!resource.source().equals("builtin") || !resource.config().path("builtinCode").asText()
                .equals(adapter.code())) {
                throw new IllegalStateException("内置插件的固定编号与已有资源冲突");
            }
            // 已有版本、管理员停用状态和调整过的授权均保留，不因重启恢复。
            if (resource.deletedAt() != null || versions.find(enterprise, versionId).isPresent()) {
                return;
            }
            resources.draft(resource, adapter.name(), adapter.description(), "builtin", config, owner, clock.instant());
        } else {
            resources.create(resourceId, enterprise, ResourceKind.PLUGIN, adapter.name(), adapter.description(),
                "builtin", owner, "builtin", config, clock.instant());
            grants.replaceGrants(enterprise, resourceId, owner,
                Set.of(new ResourceGrantSpec("enterprise", enterprise, "view"),
                    new ResourceGrantSpec("enterprise", enterprise, "use")), clock.instant());
        }
        resource = resources.find(enterprise, resourceId, false, true).orElseThrow();
        versions.publish(versionId, resource, "更新平台内置插件", owner, List.of(), clock.instant());
        tools.publish(enterprise, versionId, adapter.tools(), clock.instant());
        resources.publish(resource, versionId, clock.instant());
        audit.append(enterprise, "system:builtin-plugins", "系统", "resource.builtin.initialize", "resource",
            resourceId, "初始化平台内置插件版本",
            json.write(json.tree(
                Map.of("builtinCode", adapter.code(), "versionId", versionId, "versionNo", resource.nextVersionNo()))),
            UUID.randomUUID().toString(), clock.instant());
    }

    private ObjectNode configuration(BuiltinPluginAdapter adapter) {
        String icon = switch (adapter.code()) {
            case "platform_basics" -> "Library";
            case "todo_management" -> "NotebookPen";
            case "schedule_management" -> "GitBranch";
            case "web_read" -> "Telescope";
            default -> "Box";
        };
        String color = switch (adapter.code()) {
            case "todo_management" -> "mint";
            case "schedule_management" -> "blue";
            case "web_read" -> "amber";
            default -> "purple";
        };
        ObjectNode config = json.tree(
                Map.of("icon", icon, "color", color, "pluginType", "builtin", "builtinCode", adapter.code(),
                    "timeoutSeconds", 30, "enabledToolNames", adapter.tools().stream().map(tool -> tool.name()).toList()))
            .deepCopy();
        return config.putNull("transport").putNull("endpoint").putNull("credentialId")
            .put("implementationVersion", adapter.implementationVersion());
    }

    private String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
