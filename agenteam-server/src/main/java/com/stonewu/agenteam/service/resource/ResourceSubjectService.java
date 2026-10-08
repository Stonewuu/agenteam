package com.stonewu.agenteam.service.resource;

import com.stonewu.agenteam.mapper.resource.ResourceSubjectMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.resource.response.ResourceSubjectOption;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceGrantService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 授权管理者可以选择本企业对象，不因此获得企业成员管理或私人资料访问权限。
 */
@Service
public class ResourceSubjectService {
    private final ResourceAuthorizationService access;
    private final ResourcePolicy policy;
    private final ResourceSubjectMapper subjects;
    private final ListPagination pagination;
    private final ResourceGrantService grants;

    public ResourceSubjectService(ResourceAuthorizationService access, ResourcePolicy policy,
                                  ResourceSubjectMapper subjects, ListPagination pagination, ResourceGrantService grants) {
        this.access = access;
        this.policy = policy;
        this.subjects = subjects;
        this.pagination = pagination;
        this.grants = grants;
    }

    public PageResponse<ResourceSubjectOption> list(AuthContext actor, String resourceId, String type,
                                                    String requestedPurpose, String requestedQuery,
                                                    List<String> selected, String cursor, Integer requestedLimit) {
        String purpose = requestedPurpose == null ? "grant" : requestedPurpose;
        var resource = "owner".equals(purpose) ? access.requireGrantManager(actor, resourceId,
            false) : access.requireGrantReader(actor, resourceId);
        var extension = grants.extensionFor(type);
        if (!Set.of("grant", "owner").contains(purpose) || !"user".equals(type) && extension.isEmpty()) {
            throw ApiException.invalidField("subjectType", "当前版本不支持此授权对象类型。");
        }
        if (purpose.equals("owner")) {
            policy.editable(policy.authorize(actor, resourceId, "edit", false, false));
            if (!type.equals("user")) {
                throw ApiException.invalidField("subjectType", "资源只能转交给成员。");
            }
        }
        String query = pagination.query(requestedQuery);
        if (selected != null) {
            if (selected.isEmpty() || selected.size() > 100 || Set.copyOf(selected).size() != selected.size()) {
                throw ApiException.invalidField("subjectIds", "每次最多读取 100 个不同对象。");
            }
            selected.forEach(id -> ResourceInput.text(id, "subjectIds", 100, true));
            if (cursor != null || !query.isEmpty()) {
                throw ApiException.invalidField("subjectIds", "读取已选对象时不能同时搜索或翻页。");
            }
        }
        int limit = pagination.limit(requestedLimit);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(),
            "resources/" + resource.id() + "/subjects", purpose + ":" + type + ":" + query, "created_desc");
        var position = pagination.read(cursor, binding);
        var rows = "user".equals(type)
            ? subjects.listMembers(actor.enterpriseId(), query, purpose.equals("owner") ? resource.kind() + ".edit" : null,
                selected, position, limit)
            : extension.orElseThrow().subjects(actor, query, selected, position, limit);
        if (selected != null) {
            return new PageResponse<>(rows.stream().map(value -> value.option()).toList(), null, false);
        }
        var page = pagination.page(rows, limit, binding,
            value -> new PagePosition(value.createdAt(), value.option().id()));
        return new PageResponse<>(page.items().stream().map(value -> value.option()).toList(), page.nextCursor(),
            page.hasMore());
    }
}
