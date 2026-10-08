package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.request.MemberUpdatePayload;
import com.stonewu.agenteam.model.enterprise.response.MemberView;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.todo.TodoOrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 成员资料和组织关系按路径企业更新，不能修改全局账号或借团队分配扩大范围。
 */
@Service
public class MemberDefinitionService {
    private final EnterpriseAuthorizationService authorization;
    private final EnterpriseMapper members;
    private final IdentityViewMapper views;
    private final MemberTeamMapper teams;
    private final RoleAssignmentPolicy roles;
    private final PermissionMapper permissions;
    private final InvitationPolicy teamValidation;
    private final Clock clock;
    private final AuthMapper users;
    private final TodoOrganizationService todos;
    private final AccountBehaviorService behavior;

    public MemberDefinitionService(EnterpriseAuthorizationService authorization, EnterpriseMapper members,
                                   IdentityViewMapper views, MemberTeamMapper teams,
                                   RoleAssignmentPolicy roles, PermissionMapper permissions,
                                   InvitationPolicy teamValidation, Clock clock, AuthMapper users,
                                   TodoOrganizationService todos, AccountBehaviorService behavior) {
        this.authorization = authorization;
        this.members = members;
        this.views = views;
        this.teams = teams;
        this.roles = roles;
        this.permissions = permissions;
        this.teamValidation = teamValidation;
        this.clock = clock;
        this.users = users;
        this.todos = todos;
        this.behavior = behavior;
    }

    public void authorize(AuthContext actor, String id) {
        behavior.requireOperation(id, AccountOperation.EDIT_MEMBER);
        var scope = authorization.lockAndRequire(actor, "enterprise.members.manage");
        authorization.requireOwner(actor, scope, id);
        existing(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberView update(AuthContext actor, String id, MemberUpdatePayload value, long revision,
                             Set<String> provided) {
        authorize(actor, id);
        var before = current(actor, id, revision);
        String name = provided.contains("displayName") ? EnterpriseValidation.name(value.displayName(), "成员显示名",
            50) : before.displayName();
        Set<String> roleIds = provided.contains("roleIds") ? EnterpriseValidation.identifiers(value.roleIds(), 1, 10,
            "角色") : Set.copyOf(before.roleIds());
        Set<String> teamIds = provided.contains("teamIds") ? EnterpriseValidation.identifiers(value.teamIds(), 0, 10,
            "团队") : Set.copyOf(before.teamIds());
        if (!roleIds.equals(Set.copyOf(before.roleIds())) || !teamIds.equals(Set.copyOf(before.teamIds()))) {
            authorization.requireEnterpriseScope(authorization.require(actor, "enterprise.members.manage"));
            roles.validateExistingRoleAuthority(actor, before.roleIds());
            if (!roleIds.equals(Set.copyOf(before.roleIds()))) {
                roles.validateAssignments(actor, roleIds);
            }
            Set<String> addedTeams = new HashSet<>(teamIds);
            addedTeams.removeAll(before.teamIds());
            teamValidation.validateTeams(actor.enterpriseId(), addedTeams, id, true);
            if (!teamIds.equals(Set.copyOf(before.teamIds()))) {
                todos.requireMemberTeamsIncluded(actor.enterpriseId(), id, teamIds);
            }
        }
        if (!members.updateMember(actor.enterpriseId(), id, name, before.status(), revision, clock.instant())) {
            throw ApiException.versionConflict(revision);
        }
        permissions.replaceUserRoles(id, actor.enterpriseId(), roleIds, clock.instant());
        teams.replace(actor.enterpriseId(), id, teamIds, clock.instant());
        authorization.changed(actor, "member.update", "member", id, "修改成员资料或组织关系",
            Map.of("beforeName", before.displayName(), "afterName", name,
                "beforeRoleIds", before.roleIds(), "afterRoleIds", roleIds, "beforeTeamIds", before.teamIds(),
                "afterTeamIds", teamIds));
        return views.member(actor.enterpriseId(), id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberView status(AuthContext actor, String id, String value, long revision) {
        authorize(actor, id);
        var before = current(actor, id, revision);
        if (value == null) {
            throw ApiException.invalidField("status", "请选择启用或停用。");
        }
        String status = EnterpriseValidation.status(value, null);
        roles.validateExistingRoleAuthority(actor, before.roleIds());
        if (status.equals("active") && users.findById(id).filter(user -> user.status().equals("active")).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_DISABLED", "成员的账号已停用，不能在此恢复。");
        }
        if (!members.updateMember(actor.enterpriseId(), id, before.displayName(), status, revision, clock.instant())) {
            throw ApiException.versionConflict(revision);
        }
        authorization.changed(actor, "member.status.update", "member", id, "修改成员状态",
            Map.of("before", before.status(), "after", status));
        return views.member(actor.enterpriseId(), id);
    }

    private MemberView current(AuthContext actor, String id, long revision) {
        var view = views.member(actor.enterpriseId(), id);
        if (!view.revision().equals(Long.toString(revision))) {
            throw ApiException.versionConflict(Long.parseLong(view.revision()));
        }
        return view;
    }

    private void existing(String enterpriseId, String id) {
        if (members.findMember(enterpriseId, id).filter(member -> !member.status().equals("removed")).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "成员不存在或无法访问。");
        }
    }
}
