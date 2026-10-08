package com.stonewu.agenteam.service.skill;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.skill.SkillCandidateMapper;
import com.stonewu.agenteam.model.agent.response.EmployeeView;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.permission.entity.ResourceQueryScope;
import com.stonewu.agenteam.model.skill.response.WorkspaceSkillView;
import com.stonewu.agenteam.service.agent.EmployeeQueryService;
import com.stonewu.agenteam.service.execution.ExecutionConfigurationService;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * 工作台技能由真实雇佣和当前固定能力计算，没有可运行员工时不展示。
 */
@Service
public class WorkspaceSkillService {
    private final SkillCandidateMapper candidates;
    private final ResourceAuthorizationService resources;
    private final EnterpriseAuthorizationService authorization;
    private final PermissionMapper permissions;
    private final ExecutionConfigurationService configurations;
    private final EmployeeQueryService employees;
    private final ListPagination pagination;

    public WorkspaceSkillService(SkillCandidateMapper candidates, ResourceAuthorizationService resources,
                                 EnterpriseAuthorizationService authorization,
                                 PermissionMapper permissions, ExecutionConfigurationService configurations,
                                 EmployeeQueryService employees, ListPagination pagination) {
        this.candidates = candidates;
        this.resources = resources;
        this.authorization = authorization;
        this.permissions = permissions;
        this.configurations = configurations;
        this.employees = employees;
        this.pagination = pagination;
    }

    public PageResponse<WorkspaceSkillView> list(AuthContext actor, String requestedQuery, String cursor,
                                                 Integer requestedLimit) {
        authorization.require(actor, "workspace.view");
        authorization.require(actor, "skill.use");
        String query = pagination.query(requestedQuery);
        int limit = pagination.limit(requestedLimit);
        if (permissions.operationScope(actor.userId(), actor.enterpriseId(), "agent.run").isEmpty()) {
            return new PageResponse<>(List.of(), null, false);
        }
        var skillScope = resources.usageScope(actor, "skill");
        var agentScope = resources.usageScope(actor, "agent");
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "workspace-skills", query,
            "published_desc");
        var rows = candidates.workspace(skillScope, agentScope, actor.userId(), query, pagination.read(cursor, binding),
            100);
        var result = new ArrayList<WorkspaceSkillView>();
        Map<String, Optional<AvailableEmployee>> checked = new HashMap<>();
        int visited = 0;
        PagePosition after = null;
        for (var candidate : rows) {
            if (visited == 100 || result.size() == limit) {
                break;
            }
            visited++;
            after = new PagePosition(candidate.publishedAt(), candidate.option().versionId());
            var available = eligibleEmployees(actor, agentScope, candidate.option().versionId(), checked);
            if (!available.isEmpty()) {
                result.add(new WorkspaceSkillView(candidate.option(), available));
            }
        }
        boolean more = rows.size() > visited;
        return new PageResponse<>(List.copyOf(result), more && after != null ? pagination.encode(binding, after) : null,
            more);
    }

    private record AvailableEmployee(EmployeeView view, Set<String> skills) {
    }

    private List<EmployeeView> eligibleEmployees(AuthContext actor, ResourceQueryScope scope, String version,
                                                 Map<String, Optional<AvailableEmployee>> checked) {
        var available = new ArrayList<EmployeeView>();
        String after = null;
        while (available.size() < 100) {
            var ids = candidates.employees(scope, actor.userId(), version, after);
            for (String id : ids) {
                var employee = checked.computeIfAbsent(id, key -> usable(actor, key));
                if (employee.isPresent() && employee.get().skills().contains(version)) {
                    available.add(employee.get().view());
                }
                if (available.size() == 100) {
                    break;
                }
            }
            if (ids.size() < 100) {
                break;
            }
            after = ids.getLast();
        }
        return List.copyOf(available);
    }

    private Optional<AvailableEmployee> usable(AuthContext actor, String id) {
        try {
            var selection = configurations.inputOptions(actor, id, null);
            var view = employees.forRunPicker(actor, id);
            if (!view.canRun()) {
                return Optional.empty();
            }
            var skills = new HashSet<String>();
            selection.config().path("skillVersionIds").forEach(value -> skills.add(value.asText()));
            return Optional.of(new AvailableEmployee(view, Set.copyOf(skills)));
        } catch (ResponseStatusException unavailable) {
            if (Set.of(403, 404, 409, 422).contains(unavailable.getStatusCode().value())) {
                return Optional.empty();
            }
            throw unavailable;
        }
    }
}
