package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.service.i18n.Messages;

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
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_REVOKED", Messages.text("error.enterprise.invitationPolicy.pending.1"));
        }
        if (invitation.status().equals("accepted")) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_ACCEPTED", Messages.text("error.enterprise.invitationPolicy.pending.2"));
        }
        if (invitation.status().equals("expired") || !invitation.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_EXPIRED", Messages.text("error.enterprise.invitationPolicy.pending.3"));
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
                Messages.text("error.enterprise.invitationPolicy.validateIssuer.1"));
        }
    }

    public void validateTeams(String enterpriseId, Set<String> teamIds, String userId, boolean checkCapacity) {
        Set<String> existing = userId == null ? Set.of() : memberships.teams(enterpriseId, userId);
        for (String id : teamIds) {
            var team = enterprises.findTeam(enterpriseId, id)
                .orElseThrow(() -> ApiException.invalidField("teamIds", Messages.text("error.enterprise.invitationPolicy.validateTeams.1")));
            if (!team.status().equals("active")) {
                throw ApiException.invalidField("teamIds", Messages.text("error.enterprise.invitationPolicy.validateTeams.2"));
            }
            if (checkCapacity && !existing.contains(id) && memberships.count(enterpriseId, id) >= 500) {
                throw new ApiException(HttpStatus.CONFLICT, "TEAM_MEMBER_LIMIT", Messages.text("error.enterprise.invitationPolicy.validateTeams.3"));
            }
        }
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", Messages.text("error.enterprise.invitationPolicy.unavailable.1"));
    }
}
