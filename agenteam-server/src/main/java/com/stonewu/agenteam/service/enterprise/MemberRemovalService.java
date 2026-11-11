package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.i18n.Messages;

import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberRemovalMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.MemberRemovalState;
import com.stonewu.agenteam.model.enterprise.request.MemberRemovalRequest;
import com.stonewu.agenteam.model.enterprise.response.MemberView;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import com.stonewu.agenteam.service.resource.ResourceOwnershipPolicy;
import com.stonewu.agenteam.service.todo.TodoMemberRemovalService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 所有交接、关系解除、审计与执行资格变更共同提交，不处理中途可见的部分方案。
 */
@Service
public class MemberRemovalService {
    private final MemberRemovalPolicy policy;
    private final MemberRemovalMapper records;
    private final MemberRemovalTokenService tokens;
    private final ResourceOwnershipPolicy ownership;
    private final TodoMemberRemovalService todos;
    private final EnterpriseMapper members;
    private final MemberTeamMapper teams;
    private final PermissionMapper permissions;
    private final IdentityViewMapper views;
    private final EnterpriseAuthorizationService authorization;
    private final AuditEventService audit;
    private final Clock clock;

    public MemberRemovalService(MemberRemovalPolicy policy, MemberRemovalMapper records,
                                MemberRemovalTokenService tokens, ResourceOwnershipPolicy ownership,
                                TodoMemberRemovalService todos,
                                EnterpriseMapper members, MemberTeamMapper teams, PermissionMapper permissions,
                                IdentityViewMapper views, EnterpriseAuthorizationService authorization,
                                AuditEventService audit, Clock clock) {
        this.policy = policy;
        this.records = records;
        this.tokens = tokens;
        this.ownership = ownership;
        this.todos = todos;
        this.members = members;
        this.teams = teams;
        this.permissions = permissions;
        this.views = views;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberView remove(AuthContext actor, String member, MemberRemovalRequest plan, long revision) {
        member = policy.authorize(actor, member, true, false);
        var state = records.load(actor.enterpriseId(), member).orElseThrow(ResourceAuthorizationService::unavailable);
        tokens.verify(actor, member, state, revision, plan.impactToken());
        if (state.lastAdministrator()) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED",
                Messages.text("error.enterprise.memberRemovalService.remove.1"));
        }
        plan = validatePlan(actor, member, state, plan);
        var before = views.member(actor.enterpriseId(), member);
        var now = clock.instant();
        todos.process(actor, member, state.openTodos(), plan.todoOwnerId(), plan.cancelOpenTodos());
        if (state.hasOwnership()) {
            records.transferResources(actor.enterpriseId(), member, plan.resourceOwnerId(), state.resources().size(),
                now);
            records.transferTeams(actor.enterpriseId(), member, plan.resourceOwnerId(), state.ownedTeams().size(), now);
            var ownershipChange = Map.of("previousOwnerId", member, "ownerUserId", plan.resourceOwnerId());
            for (var resource : state.resources()) {
                audit.record(actor.enterpriseId(), actor.user(), "resource.owner.transfer", "resource", resource.id(),
                    Messages.text("text.enterprise.memberRemovalService.remove.1"), ownershipChange);
            }
            for (var team : state.ownedTeams()) {
                audit.record(actor.enterpriseId(), actor.user(), "team.owner.transfer", "team", team.id(),
                    Messages.text("text.enterprise.memberRemovalService.remove.2"), ownershipChange);
            }
        }
        teams.replace(actor.enterpriseId(), member, Set.of(), now);
        permissions.replaceUserRoles(member, actor.enterpriseId(), Set.of(), now);
        if (!members.updateMember(actor.enterpriseId(), member, before.displayName(), "removed", revision, now)) {
            throw ApiException.versionConflict(revision);
        }
        authorization.changed(actor, "member.remove", "member", member, Messages.text("text.enterprise.memberRemovalService.remove.3"),
            details(state, plan));
        return views.member(actor.enterpriseId(), member);
    }

    private MemberRemovalRequest validatePlan(AuthContext actor, String member, MemberRemovalState state,
                                              MemberRemovalRequest plan) {
        String resourceOwner = null, todoOwner = null;
        if (plan.todoOwnerId() != null && plan.cancelOpenTodos()) {
            throw ApiException.invalidField("cancelOpenTodos", Messages.text("error.enterprise.memberRemovalService.validatePlan.1"));
        }
        if (state.hasOwnership()) {
            if (!policy.canTransferOwnership(actor, member, state)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    Messages.text("error.enterprise.memberRemovalService.validatePlan.2"));
            }
            resourceOwner = ownership.requireRecipient(actor.enterpriseId(), plan.resourceOwnerId(),
                state.resourceKinds(), "resourceOwnerId");
            if (member.equals(resourceOwner)) {
                throw ApiException.invalidField("resourceOwnerId", Messages.text("error.enterprise.memberRemovalService.validatePlan.3"));
            }
        } else if (plan.resourceOwnerId() != null) {
            throw ApiException.invalidField("resourceOwnerId", Messages.text("error.enterprise.memberRemovalService.validatePlan.4"));
        }
        if (!state.openTodos().isEmpty() && plan.todoOwnerId() == null && !plan.cancelOpenTodos()) {
            throw new ApiException(HttpStatus.CONFLICT, "DEPENDENCIES_EXIST", Messages.text("error.enterprise.memberRemovalService.validatePlan.5"),
                Map.of("counts", Map.of("openTodos", state.openTodos().size())), Map.of());
        }
        if (state.openTodos().isEmpty() && (plan.todoOwnerId() != null || plan.cancelOpenTodos())) {
            throw ApiException.invalidField("todoOwnerId", Messages.text("error.enterprise.memberRemovalService.validatePlan.6"));
        }
        if (plan.todoOwnerId() != null) {
            todoOwner = members.findMember(actor.enterpriseId(), plan.todoOwnerId())
                .orElseThrow(() -> ApiException.invalidField("todoOwnerId", Messages.text("error.enterprise.memberRemovalService.validatePlan.7"))).userId();
            if (member.equals(todoOwner)) {
                throw ApiException.invalidField("todoOwnerId", Messages.text("error.enterprise.memberRemovalService.validatePlan.8"));
            }
        }
        return new MemberRemovalRequest(plan.impactToken(), resourceOwner, todoOwner, plan.cancelOpenTodos());
    }

    private Map<String, Object> details(MemberRemovalState state, MemberRemovalRequest plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("beforeRoleIds", state.roleIds());
        result.put("beforeTeamIds", state.teamIds());
        result.put("resourceCount", state.resources().size());
        result.put("ownedTeamCount", state.ownedTeams().size());
        result.put("openTodoCount", state.openTodos().size());
        result.put("activeRunCount", state.activeRuns().size());
        result.put("enabledScheduleCount", state.enabledSchedules().size());
        if (plan.resourceOwnerId() != null) {
            result.put("resourceOwnerId", plan.resourceOwnerId());
        }
        if (plan.todoOwnerId() != null) {
            result.put("todoOwnerId", plan.todoOwnerId());
        }
        result.put("cancelOpenTodos", plan.cancelOpenTodos());
        return result;
    }
}
