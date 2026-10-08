package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.resource.UsableVersionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 资源配置编辑只能选择本人有权使用的发布版本，发布时还会检查完整依赖。
 */
@Service
public class UsableVersionService {
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final ResourceAuthorizationService access;
    private final UsableVersionMapper versions;
    private final ListPagination pagination;

    public UsableVersionService(EnterpriseAuthorizationService authorization, PermissionMapper permissions,
                                ResourceAuthorizationService access,
                                UsableVersionMapper versions, ListPagination pagination) {
        this.authorization = authorization;
        this.permissions = permissions;
        this.access = access;
        this.versions = versions;
        this.pagination = pagination;
    }

    public PageResponse<UsableVersionView> list(AuthContext actor, String requestedKind, String requestedQuery,
                                                String cursor, Integer requestedLimit, List<String> selected) {
        return list(actor, requestedKind, requestedQuery, cursor, requestedLimit, selected, false);
    }

    public PageResponse<UsableVersionView> list(AuthContext actor, String requestedKind, String requestedQuery,
                                                String cursor, Integer requestedLimit, List<String> selected,
                                                boolean subagentsOnly) {
        return list(actor, requestedKind, requestedQuery, cursor, requestedLimit, selected, subagentsOnly, null);
    }

    public PageResponse<UsableVersionView> list(AuthContext actor, String requestedKind, String requestedQuery,
                                                String cursor, Integer requestedLimit, List<String> selected,
                                                boolean subagentsOnly, String resourceId) {
        return list(actor, requestedKind, requestedQuery, cursor, requestedLimit, selected, subagentsOnly, resourceId,
            false);
    }

    public PageResponse<UsableVersionView> list(AuthContext actor, String requestedKind, String requestedQuery,
                                                String cursor, Integer requestedLimit, List<String> selected,
                                                boolean subagentsOnly, String resourceId, boolean latestOnly) {
        authorization.require(actor, "capabilities.view");
        Set<String> held = Set.copyOf(permissions.listPermissionCodes(actor.userId(), actor.enterpriseId()));
        boolean editsResources = Arrays.stream(ResourceKind.values()).anyMatch(
            kind -> List.of("create", "edit", "preview").stream()
                .anyMatch(action -> held.contains(kind.permission(action))));
        if (!editsResources) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前账号没有配置资源的权限。");
        }
        var kind = ResourceInput.kind(requestedKind);
        if (subagentsOnly && kind != ResourceKind.AGENT) {
            throw ApiException.invalidField("kind", "请选择智能体资源。");
        }
        var scope = access.usageScope(actor, kind.code());
        if (resourceId != null) {
            ResourceInput.text(resourceId, "resourceId", 100, true);
            if (selected != null) {
                throw ApiException.invalidField("resourceId", "读取指定资源的版本时，不能同时提交已选版本列表。");
            }
        }
        if (selected != null) {
            if (selected.isEmpty() || selected.size() > 100 || Set.copyOf(selected).size() != selected.size()) {
                throw ApiException.invalidField("versionIds", "请选择 1～100 个不同的版本。");
            }
            selected.forEach(id -> ResourceInput.text(id, "versionIds", 100, true));
            if (cursor != null || requestedQuery != null && !requestedQuery.isBlank()) {
                throw ApiException.invalidField("versionIds", "读取已选版本时不能同时搜索或翻页。");
            }
            return new PageResponse<>(versions.selected(scope, selected), null, false);
        }
        String query = pagination.query(requestedQuery);
        int limit = pagination.limit(requestedLimit);
        boolean latest = latestOnly || subagentsOnly;
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(),
            "resources/usable-versions/" + kind.code()
                + (subagentsOnly ? "/subagents" : "") + (latest ? "/latest/" : "/versions/") + (resourceId == null ? "all" : resourceId),
            query, "published_desc");
        var rows = versions.list(scope, query, pagination.read(cursor, binding), limit, subagentsOnly, resourceId,
            latest);
        var page = pagination.page(rows, limit, binding,
            row -> new PagePosition(row.publishedAt(), row.value().versionId()));
        return new PageResponse<>(page.items().stream().map(row -> row.value()).toList(), page.nextCursor(),
            page.hasMore());
    }
}
