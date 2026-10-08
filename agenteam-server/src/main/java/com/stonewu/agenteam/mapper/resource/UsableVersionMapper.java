package com.stonewu.agenteam.mapper.resource;

import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.resource.entity.UsableVersionRecord;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 先限制使用权限、资源启停与版本资格，再按发布次序分页；不读取私有配置正文。
 */
@Repository
public class UsableVersionMapper {
    private final UsableVersionSqlMapper statements;

    public UsableVersionMapper(UsableVersionSqlMapper statements) {
        this.statements = statements;
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
                        rows.getKind(), rows.getName(), rows.getDescription(), rows.getIcon(), rows.getColor()),
                    rows.getPublishedAt().toInstant())).toList();
    }

    public List<UsableVersionView> selected(ResourceQueryScope scope, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return statements.selectedVersions(scope, ids).stream().map(rows ->
            new UsableVersionView(rows.getResourceId(), rows.getVersionId(), rows.getVersionNo(), rows.getKind(),
                rows.getName(), rows.getDescription(), rows.getIcon(), rows.getColor())).toList();
    }
}
