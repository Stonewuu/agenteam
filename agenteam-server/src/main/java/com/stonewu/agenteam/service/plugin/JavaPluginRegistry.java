package com.stonewu.agenteam.service.plugin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.plugin.JavaPluginInstallationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.model.plugin.entity.JavaPluginInstallationRow;
import com.stonewu.agenteam.model.plugin.response.JavaPluginView;
import com.stonewu.agenteam.model.tool.entity.ToolDefinition;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 所有调用按企业和不可变安装编号检查，旧版本永远不会自动路由到新版。
 */
@Service
public class JavaPluginRegistry {
    private final JavaPluginInstallationMapper installations;
    private final ResourceJson json;
    private final ObjectMapper mapper;

    public JavaPluginRegistry(JavaPluginInstallationMapper installations, ResourceJson json, ObjectMapper mapper) {
        this.installations = installations;
        this.json = json;
        this.mapper = mapper;
    }

    public JavaPluginInstallationRow find(String enterprise, String id) {
        return installations.selectOne(new LambdaQueryWrapper<JavaPluginInstallationRow>()
            .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise).eq(JavaPluginInstallationRow::getId, id));
    }

    public JavaPluginInstallationRow current(String enterprise, String pluginId) {
        return installations.selectOne(new LambdaQueryWrapper<JavaPluginInstallationRow>()
            .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise).eq(JavaPluginInstallationRow::getActiveKey, pluginId));
    }

    public Map<String, JavaPluginInstallationRow> findMany(String enterprise, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return installations.selectList(new LambdaQueryWrapper<JavaPluginInstallationRow>()
                .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise).in(JavaPluginInstallationRow::getId, ids))
            .stream().collect(Collectors.toMap(JavaPluginInstallationRow::getId, Function.identity()));
    }

    public boolean available(String enterprise, String id) {
        var row = find(enterprise, id);
        return row != null && row.getStatus().equals("active");
    }

    public Set<String> activePluginIds(String enterprise, Collection<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return installations.selectList(new LambdaQueryWrapper<JavaPluginInstallationRow>()
                .select(JavaPluginInstallationRow::getPluginId)
                .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise)
                .eq(JavaPluginInstallationRow::getStatus, "active").in(JavaPluginInstallationRow::getPluginId, ids))
            .stream().map(JavaPluginInstallationRow::getPluginId).collect(Collectors.toUnmodifiableSet());
    }

    public JavaPluginInstallationRow require(String enterprise, String id) {
        var row = find(enterprise, id);
        if (row == null || !row.getStatus().equals("active")) {
            boolean upgraded = row != null && row.getStatus().equals("upgraded");
            throw new ApiException(HttpStatus.CONFLICT, upgraded ? "JAVA_PLUGIN_UPGRADED" : "JAVA_PLUGIN_UNINSTALLED",
                upgraded ? "插件已升级，旧版本已停止调用，请确认并更新插件配置。" : "插件已卸载，已停止本次调用。");
        }
        return row;
    }

    public byte[] artifact(String enterprise, String id) {
        require(enterprise, id);
        var row = installations.selectOne(new LambdaQueryWrapper<JavaPluginInstallationRow>()
            .select(JavaPluginInstallationRow::getArtifact)
            .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise).eq(JavaPluginInstallationRow::getId, id)
            .eq(JavaPluginInstallationRow::getStatus, "active"));
        if (row == null || row.getArtifact() == null) {
            require(enterprise, id);
            throw new ApiException(HttpStatus.CONFLICT, "JAVA_PLUGIN_PACKAGE_MISSING", "插件程序包不可用，请重新安装。");
        }
        return row.getArtifact();
    }

    public List<ToolDefinition> tools(JavaPluginInstallationRow row) {
        return mapper.convertValue(json.read(row.getToolsJson()), new TypeReference<>() {
        });
    }

    public JavaPluginView view(JavaPluginInstallationRow row) {
        var active = row.getStatus().equals("active") ? row : current(row.getEnterpriseId(), row.getPluginId());
        return new JavaPluginView(row.getId(), row.getPluginId(), row.getPluginVersion(), row.getName(),
            row.getDescription(), row.getAuthor(), row.getStatus(), json.object(json.read(row.getConfigurationSchema())),
            tools(row).stream().map(tool -> new JavaPluginView.Tool(tool.name(), tool.description(), tool.operationClass(),
                json.object(tool.annotations()), json.object(tool.inputSchema()),
                tool.outputSchema() == null ? null : json.object(tool.outputSchema()))).toList(),
            active == null ? null : active.getId());
    }

    public List<JavaPluginView> list(String enterprise, String include) {
        return installations.selectList(new LambdaQueryWrapper<JavaPluginInstallationRow>()
                .eq(JavaPluginInstallationRow::getEnterpriseId, enterprise)
                .and(query -> query.eq(JavaPluginInstallationRow::getStatus, "active")
                    .or(include != null, other -> other.eq(JavaPluginInstallationRow::getId, include)))
                .orderByAsc(JavaPluginInstallationRow::getName)).stream().map(this::view).toList();
    }

    public void matchPlugin(JavaPluginInstallationRow row, String pluginId) {
        if (!Objects.equals(row.getPluginId(), pluginId)) {
            throw ApiException.invalidField("config.sources", "插件版本与所选程序包不一致。");
        }
    }
}
