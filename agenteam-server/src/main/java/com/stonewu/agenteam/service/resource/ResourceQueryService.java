package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.ResourceMapper;
import com.stonewu.agenteam.mapper.resource.ResourceVersionMapper;
import com.stonewu.agenteam.mapper.resource.ResourceViewMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.model.resource.request.ResourceListQuery;
import com.stonewu.agenteam.model.resource.response.ResourceDetailView;
import com.stonewu.agenteam.model.resource.response.ResourceSummaryView;
import com.stonewu.agenteam.model.resource.response.ResourceVersionView;
import com.stonewu.agenteam.model.resource.response.VersionSummaryView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查看资源正文与固定版本需要独立查看权限，员工介绍不经过这些入口。
 */
@Service
public class ResourceQueryService {
    private final ResourcePolicy policy;
    private final ResourceVersionMapper versions;
    private final ResourceViewMapper views;
    private final ListPagination pagination;
    private final ResourceMapper resources;
    private final ResourceAuthorizationService access;
    private final ResourceJson json;
    private final Clock clock;

    public ResourceQueryService(ResourcePolicy policy, ResourceVersionMapper versions, ResourceViewMapper views,
                                ListPagination pagination,
                                ResourceMapper resources, ResourceAuthorizationService access, ResourceJson json,
                                Clock clock) {
        this.policy = policy;
        this.versions = versions;
        this.views = views;
        this.pagination = pagination;
        this.resources = resources;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    public PageResponse<ResourceSummaryView> list(AuthContext actor, ResourceListQuery request) {
        var kind = ResourceInput.kind(request.kind());
        String status = option(request.status(), "status",
            Set.of("draft", "published", "unlisted", "disabled", "deleted"));
        String source = option(request.source(), "source", Set.of("created", "imported", "builtin"));
        Set<String> subtypes = switch (kind) {
            case AGENT -> Set.of("chat", "task", "workflow");
            case PLUGIN -> Set.of("builtin", "collection", "mcp");
            case DATA -> Set.of("file", "mysql", "http");
            default -> Set.of();
        };
        String subtype = option(request.subtype(), "subtype", subtypes);
        if ("unlisted".equals(status) && kind != ResourceKind.AGENT) {
            throw ApiException.invalidField("status", "只有数字员工可以按下架状态筛选。");
        }
        String sort = option(request.sort() == null ? "updated_desc" : request.sort(), "sort",
            Set.of("updated_desc", "created_desc", "name_asc"));
        String query = pagination.query(request.query());
        var tags = ResourceInput.tags(request.tagIds() == null ? List.of() : request.tagIds());
        int limit = pagination.limit(request.limit());
        boolean deleted = "deleted".equals(status);
        var scope = access.scope(actor, kind.code(), kind.permission("view"), ResourceCapability.VIEW, deleted);
        String filters = json.hash(json.tree(
            Map.of("query", query, "status", status == null ? "" : status, "source", source == null ? "" : source,
                "subtype", subtype == null ? "" : subtype, "tagIds", tags)));
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "resources/" + kind.code(),
            filters, sort);
        var cursor = pagination.read(request.cursor(), binding);
        if (cursor != null && sort.equals("name_asc") && cursor.sortValue() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "分页位置无法使用，请重新加载列表。");
        }
        var rows = resources.list(scope, query, status, source, subtype, tags, sort, cursor, limit,
            deleted ? clock.instant().minus(Duration.ofDays(30)) : null);
        var page = pagination.page(rows, limit, binding,
            resource -> new PagePosition(sort.equals("created_desc") ? resource.createdAt() : resource.updatedAt(),
                resource.id(), sort.equals("name_asc") ? resource.name() : null));
        return new PageResponse<>(views.summaries(actor, page.items()), page.nextCursor(), page.hasMore());
    }

    public ResourceDetailView detail(AuthContext actor, String id) {
        return views.detail(actor, policy.authorize(actor, id, "view", false, false));
    }

    public ResourceVersionView version(AuthContext actor, String id, String versionId) {
        var resource = policy.authorize(actor, id, "view", false, false);
        var version = versions.find(actor.enterpriseId(), versionId)
            .filter(item -> item.resourceId().equals(resource.id()))
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return views.version(actor, version);
    }

    public PageResponse<VersionSummaryView> versions(AuthContext actor, String id, String cursor, Integer requested) {
        var resource = policy.authorize(actor, id, "view", false, false);
        int limit = pagination.limit(requested);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(),
            "resources/" + resource.id() + "/versions", "", "published_desc");
        var rows = versions.list(actor.enterpriseId(), resource.id(), pagination.read(cursor, binding), limit);
        var page = pagination.page(rows, limit, binding,
            version -> new PagePosition(version.publishedAt(), version.id()));
        return new PageResponse<>(page.items().stream().map(views::version).toList(), page.nextCursor(),
            page.hasMore());
    }

    private String option(String value, String field, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw ApiException.invalidField(field, "请选择当前资源类型支持的筛选条件。");
        }
        return value;
    }
}
