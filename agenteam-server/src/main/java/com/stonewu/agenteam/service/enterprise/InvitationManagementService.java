package com.stonewu.agenteam.service.enterprise;

import com.stonewu.agenteam.mapper.auth.IdentityViewMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.enterprise.InvitationMapper;
import com.stonewu.agenteam.mapper.mail.MailQueueMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.InvitationEntity;
import com.stonewu.agenteam.model.enterprise.request.InvitationCreateRequest;
import com.stonewu.agenteam.model.enterprise.response.InvitationView;
import com.stonewu.agenteam.model.http.entity.PagePosition;
import com.stonewu.agenteam.model.http.response.PageResponse;
import com.stonewu.agenteam.model.mail.entity.MailDeliveryContent;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AccountInputValidation;
import com.stonewu.agenteam.service.auth.SingleUseTokenGenerator;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.model.auth.entity.AccountOperation;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.http.ListPagination;
import com.stonewu.agenteam.service.mail.MailQueueService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 邀请管理与邮件入队共用事务，所有写入在锁定企业后重新检查授权。
 */
@Service
public class InvitationManagementService {
    private final AccountBehaviorService behavior;
    private final InvitationMapper invitations;
    private final EnterpriseAuthorizationService authorization;
    private final RoleAssignmentPolicy roles;
    private final InvitationPolicy policy;
    private final SingleUseTokenGenerator tokens;
    private final MailQueueService mail;
    private final MailQueueMapper delivery;
    private final IdentityViewMapper views;
    private final AuditEventService audit;
    private final ListPagination pagination;
    private final Clock clock;
    private final EnterpriseMapper enterprises;

    public InvitationManagementService(InvitationMapper invitations, EnterpriseAuthorizationService authorization,
                                       RoleAssignmentPolicy roles,
                                       InvitationPolicy policy, SingleUseTokenGenerator tokens, MailQueueService mail,
                                       MailQueueMapper delivery,
                                       IdentityViewMapper views, AuditEventService audit, ListPagination pagination,
                                       Clock clock, EnterpriseMapper enterprises, AccountBehaviorService behavior) {
        this.behavior = behavior;
        this.invitations = invitations;
        this.authorization = authorization;
        this.roles = roles;
        this.policy = policy;
        this.tokens = tokens;
        this.mail = mail;
        this.delivery = delivery;
        this.views = views;
        this.audit = audit;
        this.pagination = pagination;
        this.clock = clock;
        this.enterprises = enterprises;
    }

    public void authorize(AuthContext actor) {
        behavior.requireOperation(actor.user().id(), AccountOperation.CREATE_INVITATION);
        behavior.requireMembershipChange(actor.enterpriseId());
        authorization.requireEnterpriseScope(authorization.lockAndRequire(actor, "enterprise.members.manage"));
    }

    public PageResponse<InvitationView> list(AuthContext actor, String cursor, Integer requestedLimit,
                                             String requestedQuery) {
        var scope = authorization.require(actor, "enterprise.members.view");
        int limit = pagination.limit(requestedLimit);
        String query = pagination.query(requestedQuery);
        var binding = new ListPagination.Binding(actor.userId(), actor.enterpriseId(), "invitations", query,
            "created_desc");
        var rows = invitations.list(actor.enterpriseId(), actor.userId(), scope, query,
            pagination.read(cursor, binding), limit + 1, clock.instant());
        return pagination.page(rows, limit, binding, row -> new PagePosition(Instant.parse(row.createdAt()), row.id()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationView create(AuthContext actor, InvitationCreateRequest request) {
        authorize(actor);
        String email = AccountInputValidation.email(request.email(), "email");
        String displayName = request.displayName() == null || request.displayName().isBlank() ? null
            : EnterpriseValidation.name(request.displayName(), "成员显示名", 50);
        Set<String> roleIds = EnterpriseValidation.identifiers(request.roleIds(), 1, 10, "角色");
        Set<String> teamIds = EnterpriseValidation.identifiers(request.teamIds(), 0, 10, "团队");
        String note = EnterpriseValidation.invitationNote(request.note());
        roles.validateAssignments(actor, roleIds);
        policy.validateTeams(actor.enterpriseId(), teamIds, null, true);
        Instant now = clock.instant();
        invitations.expirePendingEmail(actor.enterpriseId(), email, now);
        var token = tokens.issue();
        var invitation = new InvitationEntity(UUID.randomUUID().toString(), actor.enterpriseId(), email, displayName,
            teamIds.stream().sorted().toList(),
            roleIds.stream().sorted().toList(), token.hash(), "pending", actor.userId(), null,
            now.plusSeconds(7 * 86400), 1);
        try {
            invitations.insert(invitation, note, now);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_EXISTS", "此邮箱已有待接受的邀请，请查看现有记录。");
        }
        var enterprise = views.enterprise(actor.enterpriseId()).orElseThrow();
        var member = views.member(actor.enterpriseId(), actor.userId());
        mail.enqueueInvitation(actor.enterpriseId(), invitation.id(), actor.userId(), 1,
            new MailDeliveryContent(email, token.value(), enterprise.name(), member.displayName(),
                enterprises.timezone(actor.enterpriseId()), invitation.expiresAt().toString(), note));
        audit.record(actor.enterpriseId(), actor.user(), "invitation.create", "invitation", invitation.id(),
            "创建成员邀请", Map.of("roleIds", roleIds, "teamIds", teamIds));
        return invitations.view(actor.enterpriseId(), invitation.id(), now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationView revoke(AuthContext actor, String id, long revision) {
        authorize(actor);
        var invitation = pending(actor, id, revision);
        invitations.revoke(invitation, clock.instant());
        audit.record(actor.enterpriseId(), actor.user(), "invitation.revoke", "invitation", id, "撤回成员邀请",
            Map.of());
        return invitations.view(actor.enterpriseId(), id, clock.instant());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InvitationView resend(AuthContext actor, String id, long revision) {
        authorize(actor);
        var invitation = pending(actor, id, revision);
        policy.validateIssuer(invitation);
        if (invitation.email() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "INVITATION_NOT_EMAIL",
                "此邀请无需发送邮件，请复制创建时生成的邀请码或链接。");
        }
        roles.validateAssignments(actor, Set.copyOf(invitation.roleIds()));
        long retryAfter = delivery.invitationRetryAfter(actor.enterpriseId(), id, clock.instant());
        if (retryAfter > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "邀请邮件发送次数较多，请稍后重试。",
                Map.of("retryAfterSeconds", retryAfter), Map.of());
        }
        var content = mail.previousInvitation(actor.enterpriseId(), id);
        if (!tokens.hash(content.token()).equals(invitation.tokenHash()) || !Instant.parse(content.expiresAt())
            .equals(invitation.expiresAt())) {
            throw new IllegalStateException("邀请投递内容与当前有效链接不一致");
        }
        invitations.resend(invitation, clock.instant());
        mail.enqueueInvitation(actor.enterpriseId(), id, invitation.createdBy(), revision + 1, content);
        audit.record(actor.enterpriseId(), actor.user(), "invitation.resend", "invitation", id, "重新发送邀请邮件",
            Map.of());
        return invitations.view(actor.enterpriseId(), id, clock.instant());
    }

    private InvitationEntity pending(AuthContext actor, String id, long revision) {
        var invitation = invitations.lock(actor.enterpriseId(), id).orElseThrow(InvitationPolicy::unavailable);
        if (revision != invitation.revision()) {
            throw ApiException.versionConflict(invitation.revision());
        }
        policy.pending(invitation, clock.instant());
        return invitation;
    }
}
