package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.InvitationMapper;
import com.stonewu.agenteam.mapper.enterprise.MemberTeamMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.InvitationAcceptance;
import com.stonewu.agenteam.model.enterprise.entity.InvitationEntity;
import com.stonewu.agenteam.model.enterprise.request.InvitationAcceptRequest;
import com.stonewu.agenteam.model.enterprise.response.InvitationPreview;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.auth.IdentityValidation;
import com.stonewu.agenteam.service.auth.SingleUseTokenGenerator;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 接受邀请在同一事务核对身份、企业、最新角色并写入成员，不覆盖已有活跃成员。
 */
@Service
public class InvitationAcceptanceService {
    private final AccountBehaviorService behavior;
    private final InvitationMapper invitations;
    private final InvitationPolicy policy;
    private final AuthMapper users;
    private final EnterpriseMapper enterprises;
    private final IdentityViewMapper views;
    private final MemberTeamMapper teams;
    private final PermissionMapper roles;
    private final EnterpriseAuthorizationService authorization;
    private final SingleUseTokenGenerator tokens;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public InvitationAcceptanceService(InvitationMapper invitations, InvitationPolicy policy, AuthMapper users,
                                       EnterpriseMapper enterprises,
                                       IdentityViewMapper views, MemberTeamMapper teams, PermissionMapper roles,
                                       EnterpriseAuthorizationService authorization,
                                       SingleUseTokenGenerator tokens, PasswordEncoder passwords, Clock clock, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.invitations = invitations;
        this.policy = policy;
        this.users = users;
        this.enterprises = enterprises;
        this.views = views;
        this.teams = teams;
        this.roles = roles;
        this.authorization = authorization;
        this.tokens = tokens;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationPreview preview(String token, UserEntity authenticated) {
        var candidate = find(token);
        if (authenticated != null) {
            matchEmail(candidate, authenticated);
        }
        var invitation = lock(candidate);
        policy.pending(invitation, clock.instant());
        policy.validateIssuer(invitation);
        String email = invitation.email();
        String masked = null;
        if (email != null) {
            int at = email.lastIndexOf('@');
            masked = email.substring(0, email.offsetByCodePoints(0, 1)) + "***" + email.substring(at);
        }
        return new InvitationPreview(views.enterprise(invitation.enterpriseId()).orElseThrow().name(),
            views.member(invitation.enterpriseId(), invitation.createdBy()).displayName(), masked,
            invitation.expiresAt().toString());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationAcceptance accept(InvitationAcceptRequest request, UserEntity authenticated) {
        var candidate = find(request.token());
        if (authenticated != null) {
            matchEmail(candidate, authenticated);
        }
        var invitation = lock(candidate);
        UserEntity account = authenticated == null ? null : lockAccount(authenticated);
        if (account != null) {
            matchEmail(invitation, account);
        }
        if (invitation.status().equals("accepted")) {
            if (account == null) {
                throw loginRequired();
            }
            if (!account.id().equals(invitation.acceptedUserId()) || !users.isActiveMember(account.id(),
                invitation.enterpriseId())) {
                throw InvitationPolicy.unavailable();
            }
            return accepted(account, invitation.enterpriseId(), false);
        }
        policy.pending(invitation, clock.instant());
        policy.validateIssuer(invitation);
        Instant now = clock.instant();
        boolean createdAccount = account == null;
        if (createdAccount) {
            account = createAccount(request, invitation, now);
        }
        var existing = enterprises.findMember(invitation.enterpriseId(), account.id()).orElse(null);
        if (existing != null && existing.status().equals("disabled")) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMBER_DISABLED",
                "你在此企业的成员身份已停用，请联系管理员恢复。");
        }
        boolean keepExisting = existing != null && existing.status().equals("active");
        if (!keepExisting) {
            policy.validateTeams(invitation.enterpriseId(), Set.copyOf(invitation.teamIds()), account.id(), true);
            String name = invitation.displayName() == null ? account.displayName() : invitation.displayName();
            if (existing == null) {
                users.addMember(invitation.enterpriseId(), account.id(), name, now);
            } else {
                teams.rejoin(invitation.enterpriseId(), account.id(), name, now);
            }
            roles.replaceUserRoles(account.id(), invitation.enterpriseId(), Set.copyOf(invitation.roleIds()), now);
            teams.replace(invitation.enterpriseId(), account.id(), Set.copyOf(invitation.teamIds()), now);
        }
        invitations.accept(invitation, account.id(), now);
        authorization.changed(new AuthContext(account, invitation.enterpriseId(), Set.of()), "invitation.accept",
            "invitation", invitation.id(),
            keepExisting ? "接受邀请并保留已有成员权限" : "通过邀请加入企业",
            Map.of("keptExistingMembership", keepExisting, "createdAccount", createdAccount));
        return accepted(account, invitation.enterpriseId(), createdAccount);
    }

    private InvitationAcceptance accepted(UserEntity user, String enterpriseId, boolean createdAccount) {
        users.updateLastEnterprise(user.id(), enterpriseId, clock.instant());
        return new InvitationAcceptance(users.findById(user.id()).orElseThrow(), enterpriseId, createdAccount);
    }

    private UserEntity createAccount(InvitationAcceptRequest request, InvitationEntity invitation, Instant now) {
        if (invitation.email() != null && users.findByEmail(invitation.email()).isPresent()) {
            throw loginRequired();
        }
        String username = IdentityValidation.username(request.username());
        String displayName = request.displayName() == null || request.displayName().isBlank() ? username
            : EnterpriseValidation.name(request.displayName(), "显示名", 50);
        IdentityValidation.newPassword(request.password());
        String userId = UUID.randomUUID().toString();
        try {
            users.insertUser(userId, username, passwords.encode(request.password()), displayName, false, now);
            if (invitation.email() != null) {
                users.updateVerifiedEmail(userId, invitation.email(), now);
            }
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS",
                "用户名或邮箱已被使用，请登录已有账号或调整用户名。", exception);
        }
        return users.findById(userId).orElseThrow();
    }

    private UserEntity lockAccount(UserEntity previous) {
        var user = users.findByIdForUpdate(previous.id()).orElseThrow(InvitationAcceptanceService::loginRequired);
        if (!user.status().equals("active") || user.sessionVersion() != previous.sessionVersion()) {
            throw loginRequired();
        }
        return user;
    }

    private InvitationEntity find(String token) {
        return invitations.findByHash(tokens.hash(token)).orElseThrow(InvitationPolicy::unavailable);
    }

    private InvitationEntity lock(InvitationEntity candidate) {
        behavior.requireMembershipChange(candidate.enterpriseId());
        if (!enterprises.lockEnterprise(candidate.enterpriseId()).filter("active"::equals).isPresent()) {
            throw InvitationPolicy.unavailable();
        }
        return invitations.lock(candidate.enterpriseId(), candidate.id()).orElseThrow(InvitationPolicy::unavailable);
    }

    private void matchEmail(InvitationEntity invitation, UserEntity user) {
        behavior.requireOperation(user.id(), AccountOperation.ACCEPT_INVITATION);
        if (invitation.email() == null) {
            return;
        }
        if (user.emailVerifiedAt() == null || !invitation.email().equalsIgnoreCase(user.email())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVITATION_EMAIL_MISMATCH",
                "请使用受邀邮箱已完成验证的账号接受邀请。");
        }
    }

    private static ApiException loginRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请登录受邀邮箱对应的账号后继续。");
    }
}
