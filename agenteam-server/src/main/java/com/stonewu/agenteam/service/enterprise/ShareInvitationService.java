package com.stonewu.agenteam.service.enterprise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.enterprise.InvitationMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.enterprise.entity.InvitationEntity;
import com.stonewu.agenteam.model.enterprise.request.ShareInvitationRequest;
import com.stonewu.agenteam.model.enterprise.response.InvitationView;
import com.stonewu.agenteam.model.enterprise.response.IssuedInvitationView;
import com.stonewu.agenteam.model.http.entity.ApiOperationResult;
import com.stonewu.agenteam.model.security.entity.EncryptedPayload;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.SingleUseTokenGenerator;
import com.stonewu.agenteam.service.http.IdempotentRequestService;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 分享凭据只在授权的创建响应中解密，重复请求记录保存加密内容。
 */
@Service
public class ShareInvitationService {
    public record StoredIssue(InvitationView invitation, EncryptedPayload credential) {
    }

    private final AuthContextService identity;
    private final InvitationManagementService management;
    private final RoleAssignmentPolicy roles;
    private final InvitationPolicy policy;
    private final InvitationMapper invitations;
    private final SingleUseTokenGenerator tokens;
    private final IdempotentRequestService requests;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final AuditEventService audit;
    private final Clock clock;

    public ShareInvitationService(AuthContextService identity, InvitationManagementService management,
                                  RoleAssignmentPolicy roles,
                                  InvitationPolicy policy, InvitationMapper invitations, SingleUseTokenGenerator tokens,
                                  IdempotentRequestService requests,
                                  PayloadEncryption encryption, ObjectMapper json, AuditEventService audit,
                                  Clock clock) {
        this.identity = identity;
        this.management = management;
        this.roles = roles;
        this.policy = policy;
        this.invitations = invitations;
        this.tokens = tokens;
        this.requests = requests;
        this.encryption = encryption;
        this.json = json;
        this.audit = audit;
        this.clock = clock;
    }

    public ApiOperationResult create(String enterpriseId, ShareInvitationRequest payload, HttpServletRequest request) {
        var actor = identity.requireEnterprise(request.getSession(false), enterpriseId);
        var result = requests.execute(request, actor.user(), enterpriseId, Set.of("/roleIds", "/teamIds"),
            () -> management.authorize(actor), () -> ApiOperationResult.of(201, issue(actor, payload)));
        var stored = json.convertValue(result.data(), StoredIssue.class);
        String code = encryption.decrypt(stored.credential(), binding(actor, stored.invitation().id()), String.class);
        return new ApiOperationResult(result.status(),
            new IssuedInvitationView(stored.invitation(), code, "/register#token=" + code), result.replayed());
    }

    private StoredIssue issue(AuthContext actor, ShareInvitationRequest request) {
        Set<String> roleIds = EnterpriseValidation.identifiers(request.roleIds(), 1, 10, "角色");
        Set<String> teamIds = EnterpriseValidation.identifiers(request.teamIds(), 0, 10, "团队");
        String name = request.displayName() == null || request.displayName().isBlank() ? null
            : EnterpriseValidation.name(request.displayName(), "成员显示名", 50);
        String note = EnterpriseValidation.invitationNote(request.note());
        roles.validateAssignments(actor, roleIds);
        policy.validateTeams(actor.enterpriseId(), teamIds, null, true);
        var now = clock.instant();
        var token = tokens.issue();
        var invitation = new InvitationEntity(UUID.randomUUID().toString(), actor.enterpriseId(), null, name,
            teamIds.stream().sorted().toList(), roleIds.stream().sorted().toList(), token.hash(), "pending",
            actor.userId(), null, now.plus(Duration.ofDays(7)), 1);
        invitations.insert(invitation, note, now);
        audit.record(actor.enterpriseId(), actor.user(), "invitation.create", "invitation", invitation.id(),
            "创建邀请码与邀请链接", Map.of("roleIds", roleIds, "teamIds", teamIds));
        return new StoredIssue(invitations.view(actor.enterpriseId(), invitation.id(), now),
            encryption.encrypt(token.value(), binding(actor, invitation.id())));
    }

    private String binding(AuthContext actor, String id) {
        return "invitation:" + actor.enterpriseId() + ":" + actor.userId() + ":" + id;
    }
}
