package com.stonewu.agenteam.service.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.auth.AuthTokenMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.mail.MailDeliveryStateMapper;
import com.stonewu.agenteam.mapper.mail.MailQueueMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.background.entity.JobLease;
import com.stonewu.agenteam.model.mail.entity.MailDeliveryContent;
import com.stonewu.agenteam.model.mail.entity.MailJobPayload;
import com.stonewu.agenteam.model.mail.entity.PasswordResetLookupContent;
import com.stonewu.agenteam.model.mail.entity.PreparedMail;
import com.stonewu.agenteam.model.permission.entity.DataScope;
import com.stonewu.agenteam.service.auth.SingleUseTokenGenerator;
import com.stonewu.agenteam.service.enterprise.RoleAssignmentPolicy;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 发送前验证业务依据；投递结果与当前邀请状态在短事务内一并保存。
 */
@Service
public class MailDeliveryService {
    private final BackgroundJobMapper jobs;
    private final MailQueueMapper queueMapper;
    private final MailQueueService queue;
    private final MailDeliveryStateMapper state;
    private final AuthMapper users;
    private final AuthTokenMapper tokens;
    private final SingleUseTokenGenerator generator;
    private final PayloadEncryption encryption;
    private final MailTemplates templates;
    private final EnterpriseMapper enterprises;
    private final RoleAssignmentPolicy roles;
    private final PermissionMapper permissions;
    private final ObjectMapper json;
    private final Clock clock;

    public MailDeliveryService(BackgroundJobMapper jobs, MailQueueMapper queueMapper, MailQueueService queue,
                               MailDeliveryStateMapper state,
                               AuthMapper users, AuthTokenMapper tokens, SingleUseTokenGenerator generator,
                               PayloadEncryption encryption,
                               MailTemplates templates, EnterpriseMapper enterprises, RoleAssignmentPolicy roles,
                               PermissionMapper permissions,
                               ObjectMapper json, Clock clock) {
        this.jobs = jobs;
        this.queueMapper = queueMapper;
        this.queue = queue;
        this.state = state;
        this.users = users;
        this.tokens = tokens;
        this.generator = generator;
        this.encryption = encryption;
        this.templates = templates;
        this.enterprises = enterprises;
        this.roles = roles;
        this.permissions = permissions;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<PreparedMail> prepare(JobLease lease) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!jobs.lockOwned(lease, now)) {
            return Optional.empty();
        }
        MailJobPayload payload = queue.parse(lease.payloadJson());
        if (lease.exhausted()) {
            finish(lease, payload, "failed", "MAIL_RETRY_EXHAUSTED", "邮件发送未完成，请重新发送。", now, now);
            return Optional.empty();
        }
        if (payload.purpose().equals("password_reset_lookup")) {
            payload = resolveReset(lease, payload, now);
            if (payload == null) {
                jobs.finish(lease, "completed", null, null, now, now);
                return Optional.empty();
            }
        }
        MailDeliveryContent content = encryption.decrypt(payload.content(), lease.id(), MailDeliveryContent.class);
        boolean valid = payload.purpose().equals("enterprise_invitation")
            ? usableInvitation(lease, payload, content, now) : usableToken(lease, payload, content, now);
        if (!valid) {
            finish(lease, payload, "cancelled", "MAIL_TARGET_UNAVAILABLE", "链接已失效或邀请已变更，未发送邮件。", now,
                now);
            return Optional.empty();
        }
        if (payload.purpose().equals("enterprise_invitation")) {
            state.invitationAttempt(lease.enterpriseId(), payload.targetId(), lease.id(), now);
        }
        return Optional.of(templates.render(lease.id(), payload.purpose(), content));
    }

    private MailJobPayload resolveReset(JobLease lease, MailJobPayload payload, Instant now) {
        if (lease.enterpriseId() != null) {
            throw new IllegalStateException("全局找回请求不能携带企业归属");
        }
        var lookup = encryption.decrypt(payload.content(), lease.id(), PasswordResetLookupContent.class);
        Instant expires = Instant.parse(lookup.expiresAt()).truncatedTo(ChronoUnit.MILLIS);
        if (!expires.isAfter(now)) {
            return null;
        }
        var found = users.findByLoginIdentifier(lookup.identifier()).orElse(null);
        if (found == null) {
            return null;
        }
        var user = users.findByIdForUpdate(found.id()).orElseThrow();
        if (!"active".equals(user.status()) || user.email() == null || user.emailVerifiedAt() == null) {
            return null;
        }
        if (lookup.identifier().contains("@") && !lookup.identifier().equalsIgnoreCase(user.email())) {
            return null;
        }
        if (tokens.mailRetryAfter(user.id(), "password_reset", now) > 0) {
            return null;
        }
        var token = generator.issue();
        tokens.insert(token, user.id(), "password_reset", user.email(), expires, now);
        var content = new MailDeliveryContent(user.email(), token.value(), "", "", "Asia/Shanghai", expires.toString());
        var resolved = new MailJobPayload("password_reset", token.id(), encryption.encrypt(content, lease.id()));
        if (!queueMapper.replacePayload(lease, user.id(), queue.serialize(resolved), now)) {
            throw new IllegalStateException("找回邮件的领取资格已失效");
        }
        return resolved;
    }

    private boolean usableToken(JobLease lease, MailJobPayload payload, MailDeliveryContent content, Instant now) {
        if (lease.enterpriseId() != null || !Set.of("password_reset", "email_verify").contains(payload.purpose())) {
            return false;
        }
        if (lease.ownerUserId() == null && !queue.parse(lease.payloadJson()).purpose()
            .equals("password_reset_lookup")) {
            return false;
        }
        var token = tokens.findById(payload.targetId()).orElse(null);
        if (token == null || !token.purpose()
            .equals(payload.purpose()) || token.consumedAt() != null || !token.expiresAt().isAfter(now)
            || !token.tokenHash()
            .equals(generator.hash(content.token())) || token.targetEmail() == null || !token.targetEmail()
            .equalsIgnoreCase(content.recipient())
            || !token.expiresAt().equals(Instant.parse(content.expiresAt()))) {
            return false;
        }
        if (lease.ownerUserId() != null && !lease.ownerUserId().equals(token.userId())) {
            return false;
        }
        var user = users.findById(token.userId()).orElse(null);
        return user != null && user.status().equals("active") && (!payload.purpose().equals("password_reset")
            || (user.emailVerifiedAt() != null && content.recipient().equalsIgnoreCase(user.email())));
    }

    private boolean usableInvitation(JobLease lease, MailJobPayload payload, MailDeliveryContent content, Instant now) {
        if (queueMapper.latestInvitation(lease.enterpriseId(), payload.targetId())
            .filter(job -> job.id().equals(lease.id())).isEmpty()) {
            return false;
        }
        var target = state.invitation(lease.enterpriseId(), payload.targetId()).orElse(null);
        if (target == null || !target.creatorId().equals(lease.ownerUserId()) || !target.status()
            .equals("pending") || !target.expiresAt().isAfter(now)
            || !target.enterpriseStatus().equals("active") || !target.memberStatus()
            .equals("active") || !target.userStatus().equals("active")
            || !target.email().equalsIgnoreCase(content.recipient()) || !target.tokenHash()
            .equals(generator.hash(content.token()))
            || !target.expiresAt().equals(Instant.parse(content.expiresAt()))) {
            return false;
        }
        try {
            var creator = users.findById(target.creatorId()).orElseThrow();
            if (permissions.operationScope(creator.id(), target.enterpriseId(), "enterprise.members.manage")
                .filter(scope -> scope == DataScope.ENTERPRISE).isEmpty()) {
                return false;
            }
            var actor = new AuthContext(creator, target.enterpriseId(),
                Set.copyOf(permissions.listPermissionCodes(creator.id(), target.enterpriseId())));
            List<String> roleIds = json.readValue(target.roleIdsJson(), new TypeReference<>() {
            });
            List<String> teamIds = json.readValue(target.teamIdsJson(), new TypeReference<>() {
            });
            if (roleIds.isEmpty()) {
                return false;
            }
            roles.validateAssignments(actor, Set.copyOf(roleIds));
            return teamIds.stream().allMatch(
                id -> enterprises.findTeam(target.enterpriseId(), id).map(team -> team.status().equals("active"))
                    .orElse(false));
        } catch (ResponseStatusException exception) {
            return false;
        } catch (Exception exception) {
            throw new IllegalStateException("邀请的角色或团队记录无法读取");
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void succeeded(JobLease lease) {
        Instant now = clock.instant();
        if (!jobs.lockOwned(lease, now)) {
            return;
        }
        finish(lease, queue.parse(lease.payloadJson()), "completed", null, null, now, now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void failed(JobLease lease, String code, String message, boolean retryable) {
        Instant now = clock.instant();
        if (!jobs.lockOwned(lease, now)) {
            return;
        }
        boolean retry = retryable && lease.attemptCount() < lease.maxAttempts();
        Instant next = retry ? now.plusSeconds(lease.attemptCount() == 1 ? 60 : 300) : now;
        MailJobPayload payload;
        try {
            payload = queue.parse(lease.payloadJson());
        } catch (RuntimeException exception) {
            jobs.finish(lease, "failed", code, message, now, now);
            return;
        }
        finish(lease, payload, retry ? "queued" : "failed", code, message, next, now);
    }

    private void finish(JobLease lease, MailJobPayload payload, String status, String code, String message,
                        Instant available, Instant now) {
        if (!jobs.finish(lease, status, code, message, available, now)) {
            return;
        }
        if (payload.purpose().equals("enterprise_invitation")) {
            state.invitationResult(lease.enterpriseId(), payload.targetId(), lease.id(),
                status.equals("completed") ? "sent" : "failed", message, now);
        }
    }
}
