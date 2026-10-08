package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.request.MemberCreateRequest;
import com.stonewu.agenteam.model.enterprise.response.MemberView;
import com.stonewu.agenteam.service.auth.IdentityValidation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 账号、成员、角色和团队一并提交，任何一步失败都不留下可登录的孤立账号。
 */
@Service
public class MemberCreationService {
    private final InvitationManagementService management;
    private final RoleAssignmentPolicy roles;
    private final InvitationPolicy policy;
    private final AuthMapper users;
    private final PermissionMapper permissions;
    private final MemberTeamMapper teams;
    private final IdentityViewMapper views;
    private final PasswordEncoder passwords;
    private final EnterpriseAuthorizationService authorization;
    private final Clock clock;

    public MemberCreationService(InvitationManagementService management, RoleAssignmentPolicy roles,
                                 InvitationPolicy policy,
                                 AuthMapper users, PermissionMapper permissions, MemberTeamMapper teams,
                                 IdentityViewMapper views,
                                 PasswordEncoder passwords, EnterpriseAuthorizationService authorization, Clock clock) {
        this.management = management;
        this.roles = roles;
        this.policy = policy;
        this.users = users;
        this.permissions = permissions;
        this.teams = teams;
        this.views = views;
        this.passwords = passwords;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MemberView create(AuthContext actor, MemberCreateRequest request) {
        management.authorize(actor);
        String username = IdentityValidation.username(request.username());
        IdentityValidation.newPassword(request.password());
        String name = request.displayName() == null || request.displayName().isBlank() ? username
            : EnterpriseValidation.name(request.displayName(), "显示名", 50);
        Set<String> roleIds = EnterpriseValidation.identifiers(request.roleIds(), 1, 10, "角色");
        Set<String> teamIds = EnterpriseValidation.identifiers(request.teamIds(), 0, 10, "团队");
        roles.validateAssignments(actor, roleIds);
        policy.validateTeams(actor.enterpriseId(), teamIds, null, true);
        var now = clock.instant();
        String id = UUID.randomUUID().toString();
        try {
            users.insertUser(id, username, passwords.encode(request.password()), name, false, now);
        } catch (DuplicateKeyException failure) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", "用户名已被使用，请使用其他用户名。", failure);
        }
        users.addMember(actor.enterpriseId(), id, name, now);
        permissions.replaceUserRoles(id, actor.enterpriseId(), roleIds, now);
        teams.replace(actor.enterpriseId(), id, teamIds, now);
        users.updateLastEnterprise(id, actor.enterpriseId(), now);
        authorization.changed(actor, "member.create", "member", id, "管理员创建成员账号",
            Map.of("roleIds", roleIds, "teamIds", teamIds));
        return views.member(actor.enterpriseId(), id);
    }
}
