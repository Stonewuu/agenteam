package com.stonewu.agenteam.service.agent;

import com.stonewu.agenteam.mapper.agent.EmployeeMapper;
import com.stonewu.agenteam.mapper.permission.ResourceAuthorizationMapper;
import com.stonewu.agenteam.mapper.resource.ResourceJson;
import com.stonewu.agenteam.mapper.resource.TagMapper;
import com.stonewu.agenteam.mapper.resource.UsableVersionMapper;
import com.stonewu.agenteam.model.agent.entity.EmployeeRecord;
import com.stonewu.agenteam.model.agent.response.EmployeeView;
import com.stonewu.agenteam.model.agent.response.EmployeeView.Skill;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.model.resource.response.UsableVersionView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceInput;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 员工介绍只使用发布公开信息；本人已建立的关系与能否继续运行分别呈现。
 */
@Service
public class EmployeeQueryService {
    private final EmployeeMapper employees;
    private final ResourceAuthorizationService access;
    private final ResourceAuthorizationMapper grants;
    private final UsableVersionMapper versions;
    private final TagMapper tags;
    private final ResourceJson json;
    private final ListPagination pagination;
    private final Clock clock;

    public EmployeeQueryService(EmployeeMapper employees, ResourceAuthorizationService access,
                                ResourceAuthorizationMapper grants,
                                UsableVersionMapper versions, TagMapper tags, ResourceJson json,
                                ListPagination pagination, Clock clock) {
        this.employees = employees;
        this.access = access;
        this.grants = grants;
        this.versions = versions;
        this.tags = tags;
        this.json = json;
        this.pagination = pagination;
        this.clock = clock;
    }

    public PageResponse<EmployeeView> list(AuthContext actor, String requestedTab, String requestedQuery,
                                           List<String> selectedTags, String cursor, Integer requestedLimit) {
        String tab = requestedTab == null ? "market" : requestedTab;
        if (!Set.of("market", "mine").contains(tab)) {
            throw ApiException.invalidField("tab", "请选择员工广场或我的员工。");
        }
        String query = pagination.query(requestedQuery);
        var tagIds = ResourceInput.tags(selectedTags == null ? List.of() : selectedTags);
        int limit = pagination.limit(requestedLimit);
        var scope = tab.equals("mine") && actor.permissions().contains("agent.run") ? access.usageScope(actor, "agent")
            : access.scope(actor, "agent", "agent.market_view", ResourceCapability.USE);
        String filters = json.hash(json.tree(Map.of("tab", tab, "query", query, "tagIds", tagIds)));
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "employees", filters,
            tab.equals("mine") ? "recent_use_desc" : "published_desc");
        var rows = employees.list(actor.enterpriseId(), actor.userId(), tab.equals("mine"), scope, query, tagIds,
            pagination.read(cursor, binding), limit, clock.instant());
        var page = pagination.page(rows, limit, binding,
            employee -> new PagePosition(employee.positionTime(), employee.id()));
        return new PageResponse<>(views(actor, page.items()), page.nextCursor(), page.hasMore());
    }

    public EmployeeView detail(AuthContext actor, String id) {
        if (actor.permissions().contains("agent.run")) {
            var own = employees.find(actor.enterpriseId(), actor.userId(), id, access.usageScope(actor, "agent"),
                    clock.instant())
                .filter(value -> Set.of("active", "paused").contains(value.hireStatus()));
            if (own.isPresent()) {
                return views(actor, List.of(own.get())).getFirst();
            }
        }
        var scope = access.scope(actor, "agent", "agent.market_view", ResourceCapability.USE);
        var employee = employees.find(actor.enterpriseId(), actor.userId(), id, scope, clock.instant())
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return views(actor, List.of(employee)).getFirst();
    }

    public EmployeeView forRunPicker(AuthContext actor, String id) {
        var scope = access.usageScope(actor, "agent");
        var employee = employees.find(actor.enterpriseId(), actor.userId(), id, scope, clock.instant())
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return views(actor, List.of(employee)).getFirst();
    }

    public List<EmployeeView> recentAvailable(AuthContext actor) {
        return views(actor,
            employees.recentAvailable(actor.userId(), access.usageScope(actor, "agent"), clock.instant()));
    }

    private List<EmployeeView> views(AuthContext actor, List<EmployeeRecord> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var ids = rows.stream().map(EmployeeRecord::id).toList();
        var labels = tags.forResources(actor.enterpriseId(), ids);
        var skillIds = rows.stream().flatMap(row -> row.skillVersions().stream()).distinct().toList();
        var visibleSkills = skills(actor, skillIds);
        var permitted = permitted(actor, "agent.run", ids);
        var hireable = permitted(actor, "agent.hire", ids);
        var discoverable = permitted(actor, "agent.market_view", ids);
        return rows.stream().map(row -> {
            boolean available = row.resourceStatus().equals("active") && row.ownerActive() && row.versionStatus()
                .equals("available") && row.modelAvailable();
            boolean canHire = Set.of("none", "terminated")
                .contains(row.hireStatus()) && row.listed() && hireable.contains(row.id()) && discoverable.contains(
                row.id())
                && available;
            boolean canResume = row.hireStatus().equals("paused") && hireable.contains(row.id()) && available;
            String reason = unavailable(row, permitted.contains(row.id()), canHire);
            var skills = row.skillVersions().stream().map(visibleSkills::get).filter(Objects::nonNull)
                .map(skill -> new Skill(skill.resourceId(), skill.name(), skill.description())).toList();
            return new EmployeeView(row.id(), row.name(), row.description(), row.businessRole(), row.icon(),
                row.color(), row.examples(),
                row.welcomeMessage(), row.suggestedQuestions(),
                labels.getOrDefault(row.id(), List.of()).stream().map(tag -> tag.name()).toList(), row.hireId(),
                row.hireRevision(), row.applicationId(), row.applicationRevision(), row.hireStatus(),
                row.requiresApproval(), canHire, canResume, reason == null, reason, skills, row.attachmentsEnabled());
        }).toList();
    }

    private Map<String, UsableVersionView> skills(AuthContext actor, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return versions.selected(access.usageScope(actor, "skill"), ids).stream()
                .collect(Collectors.toMap(UsableVersionView::versionId, Function.identity()));
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().value() == 403 || denied.getStatusCode().value() == 404) {
                return Map.of();
            }
            throw denied;
        }
    }

    private Set<String> permitted(AuthContext actor, String operation, List<String> ids) {
        try {
            return grants.visibleIds(ids, access.scope(actor, "agent", operation, ResourceCapability.USE));
        } catch (ResponseStatusException denied) {
            if (denied.getStatusCode().value() == 403 || denied.getStatusCode().value() == 404) {
                return Set.of();
            }
            throw denied;
        }
    }

    private String unavailable(EmployeeRecord employee, boolean allowed, boolean canHire) {
        if (employee.resourceStatus().equals("deleted")) {
            return "该员工已删除，无法开始任务。";
        }
        if (!employee.resourceStatus().equals("active")) {
            return "该员工已停用。";
        }
        if (!employee.ownerActive() || !employee.versionStatus().equals("available")) {
            return "该员工暂时不可使用，请联系管理员。";
        }
        if (!allowed) {
            return "当前没有使用该员工的权限。";
        }
        return switch (employee.hireStatus()) {
            case "active" -> null;
            case "paused" -> "雇佣已暂停。";
            case "pending" -> "雇佣申请仍待审批。";
            case "terminated" -> employee.listed() ? "雇佣已解除。" : "该员工尚未重新上架，暂时不能再次雇佣。";
            default -> canHire ? "请先雇佣该员工。" : "当前没有雇佣该员工的权限。";
        };
    }
}
