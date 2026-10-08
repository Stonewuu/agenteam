package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.InvitationEntity;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Set;

/**
 * 邀请未接受前，发起人的授权以及所选团队和角色必须仍然有效。
 */
@Service
public class InvitationPolicy {
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final EnterpriseAuthorizationService authorization;
    private final RoleAssignmentPolicy roles;
    private final MemberTeamMapper memberships;

    public InvitationPolicy(AuthMapper users, EnterpriseMapper enterprises,
                            EnterpriseAuthorizationService authorization,
                            RoleAssignmentPolicy roles, MemberTeamMapper memberships) {
        this.users = users;
        this.enterprises = enterprises;
        this.authorization = authorization;
        this.roles = roles;
        this.memberships = memberships;
    }

    public void pending(InvitationEntity invitation, Instant now) {
        if (invitation.status().equals("revoked")) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_REVOKED", "邀请已撤回，请联系邀请人。");
        }
        if (invitation.status().equals("accepted")) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ACCEPTED", "邀请已经接受，请登录后打开企业。");
        }
        if (invitation.status().equals("expired") || !invitation.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_EXPIRED", "邀请已过期，请联系邀请人重新邀请。");
        }
    }

    public void validateIssuer(InvitationEntity invitation) {
        try {
            var issuer = users.findById(invitation.createdBy()).orElseThrow(InvitationPolicy::unavailable);
            var context = new AuthContext(issuer, invitation.enterpriseId(), Set.of());
            authorization.requireEnterpriseScope(authorization.require(context, "enterprise.members.manage"));
            roles.validateAssignments(context, Set.copyOf(invitation.roleIds()));
            validateTeams(invitation.enterpriseId(), Set.copyOf(invitation.teamIds()), null, false);
        } catch (ResponseStatusException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_CHANGED",
                "邀请所需的权限、角色或团队已变化，请联系邀请人重新邀请。");
        }
    }

    public void validateTeams(String enterpriseId, Set<String> teamIds, String userId, boolean checkCapacity) {
        Set<String> existing = userId == null ? Set.of() : memberships.teams(enterpriseId, userId);
        for (String id : teamIds) {
            var team = enterprises.findTeam(enterpriseId, id)
                .orElseThrow(() -> ApiException.invalidField("teamIds", "所选团队不存在或不属于当前企业。"));
            if (!team.status().equals("active")) {
                throw ApiException.invalidField("teamIds", "所选团队已停用，请调整后重试。");
            }
            if (checkCapacity && !existing.contains(id) && memberships.count(enterpriseId, id) >= 500) {
                throw new ApiException(HttpStatus.CONFLICT, "TEAM_MEMBER_LIMIT", "所选团队人数已满，请联系邀请人调整。");
            }
        }
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "邀请不存在或无法访问。");
    }
}
