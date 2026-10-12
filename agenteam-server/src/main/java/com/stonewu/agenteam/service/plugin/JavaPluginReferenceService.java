package com.stonewu.agenteam.service.plugin;

import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;

/**
 * 配置页读取旧引用的真实失效原因，查询不会更新智能体或插件定义。
 */
@Service
public class JavaPluginReferenceService {
    private final ResourceVersionMapper versions;
    private final ResourceAuthorizationService access;
    private final JavaPluginRegistry registry;

    public JavaPluginReferenceService(ResourceVersionMapper versions, ResourceAuthorizationService access,
                                       JavaPluginRegistry registry) {
        this.versions = versions;
        this.access = access;
        this.registry = registry;
    }

    public record Notice(String versionId, String resourceId, String name, String status) {
    }

    public List<Notice> notices(AuthContext actor, List<String> ids) {
        if (ids.size() > 100) {
            throw ApiException.invalidField("versionIds", "一次最多检查 100 个插件版本。");
        }
        var result = new ArrayList<Notice>();
        var selected = versions.findMany(actor.enterpriseId(), ids).values();
        var installationIds = new HashSet<String>();
        for (var version : selected) {
            access.requireUse(actor, version.resourceId(), "plugin");
            for (var source : version.config().path("sources")) {
                if (source.path("type").asText().equals("java")) {
                    installationIds.add(source.path("versionId").asText());
                }
            }
        }
        var installed = registry.findMany(actor.enterpriseId(), installationIds);
        var activePluginIds = registry.activePluginIds(actor.enterpriseId(), installed.values().stream()
            .map(installation -> installation.getPluginId()).distinct().toList());
        for (var version : selected) {
            for (var source : version.config().path("sources")) {
                if (!source.path("type").asText().equals("java")) {
                    continue;
                }
                var installation = installed.get(source.path("versionId").asText());
                if (installation == null || !installation.getStatus().equals("active")) {
                    result.add(new Notice(version.id(), version.resourceId(), version.name(),
                        installation != null && activePluginIds.contains(installation.getPluginId()) ? "upgraded" : "uninstalled"));
                }
            }
        }
        return List.copyOf(result);
    }
}
