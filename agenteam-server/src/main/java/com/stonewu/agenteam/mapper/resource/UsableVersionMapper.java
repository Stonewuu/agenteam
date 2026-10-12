package com.stonewu.agenteam.mapper.resource;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.UsableVersionRecord;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.HashSet;

/**
 * 先限制使用权限、资源启停与版本资格，再按发布次序分页；不读取私有配置正文。
 */
@Repository
public class UsableVersionMapper {
    private final UsableVersionSqlMapper statements;
    private final ResourceJson json;

    public UsableVersionMapper(UsableVersionSqlMapper statements, ResourceJson json) {
        this.statements = statements;
        this.json = json;
    }

    public List<UsableVersionRecord> list(ResourceQueryScope scope, String query, PagePosition cursor, int limit) {
        return list(scope, query, cursor, limit, false);
    }

    public List<UsableVersionRecord> list(ResourceQueryScope scope, String query, PagePosition cursor, int limit,
                                          boolean subagentsOnly) {
        return list(scope, query, cursor, limit, subagentsOnly, null);
    }

    public List<UsableVersionRecord> list(ResourceQueryScope scope, String query, PagePosition cursor, int limit,
                                          boolean subagentsOnly, String resourceId) {
        return list(scope, query, cursor, limit, subagentsOnly, resourceId, subagentsOnly);
    }

    public List<UsableVersionRecord> list(ResourceQueryScope scope, String query, PagePosition cursor, int limit,
                                          boolean subagentsOnly, String resourceId, boolean latestOnly) {
        return statements.listVersions(scope, query, cursor, limit + 1, subagentsOnly, resourceId, latestOnly).stream()
            .map(rows ->
                new UsableVersionRecord(
                    new UsableVersionView(rows.getResourceId(), rows.getVersionId(), rows.getVersionNo(),
                        rows.getKind(), rows.getName(), rows.getDescription(), rows.getIcon(), rows.getColor(),
                        pluginTypes(rows.getPluginTypesJson())),
                    rows.getPublishedAt().toInstant())).toList();
    }

    public List<UsableVersionView> selected(ResourceQueryScope scope, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return statements.selectedVersions(scope, ids).stream().map(rows ->
            new UsableVersionView(rows.getResourceId(), rows.getVersionId(), rows.getVersionNo(), rows.getKind(),
                rows.getName(), rows.getDescription(), rows.getIcon(), rows.getColor(),
                pluginTypes(rows.getPluginTypesJson()))).toList();
    }

    private List<String> pluginTypes(String value) {
        var types = new HashSet<String>();
        if (value != null) {
            json.read(value).forEach(type -> types.add(type.asText()));
        }
        return List.of("builtin", "mcp", "java").stream().filter(types::contains).toList();
    }
}
