package com.stonewu.agenteam.service.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.mapper.background.BackgroundJobMapper;
import com.stonewu.agenteam.mapper.mail.MailQueueMapper;
import com.stonewu.agenteam.model.auth.entity.IssuedToken;
import com.stonewu.agenteam.model.mail.entity.MailDeliveryContent;
import com.stonewu.agenteam.model.mail.entity.MailJobPayload;
import com.stonewu.agenteam.model.mail.entity.PasswordResetLookupContent;
import com.stonewu.agenteam.service.security.PayloadEncryption;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 只把邮件加入持久队列，邮件服务调用不进入创建账户或邀请的事务。
 */
@Service
public class MailQueueService {
    private final BackgroundJobMapper jobs;
    private final MailQueueMapper mail;
    private final PayloadEncryption encryption;
    private final ObjectMapper json;
    private final Clock clock;

    public MailQueueService(BackgroundJobMapper jobs, MailQueueMapper mail, PayloadEncryption encryption,
                            ObjectMapper json, Clock clock) {
        this.jobs = jobs;
        this.mail = mail;
        this.encryption = encryption;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public void enqueuePasswordResetLookup(String identifier) {
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        var sealed = encryption.encrypt(new PasswordResetLookupContent(identifier, now.plusSeconds(1800).toString()),
            id);
        var payload = new MailJobPayload("password_reset_lookup", id, sealed);
        jobs.enqueue(id, null, null, "mail", "mail:reset-lookup:" + id, serialize(payload), now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueAccountVerification(IssuedToken token, String userId, String recipient, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        var content = new MailDeliveryContent(recipient, token.value(), "", "", "Asia/Shanghai", expiresAt.toString());
        var payload = new MailJobPayload("email_verify", token.id(), encryption.encrypt(content, id));
        jobs.enqueue(id, null, userId, "mail", "mail:email-verify:" + token.id(), serialize(payload), clock.instant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueInvitation(String enterpriseId, String invitationId, String ownerUserId, long revision,
                                  MailDeliveryContent content) {
        String id = UUID.randomUUID().toString();
        var payload = new MailJobPayload("enterprise_invitation", invitationId, encryption.encrypt(content, id));
        jobs.enqueue(id, enterpriseId, ownerUserId, "mail", MailQueueMapper.invitationKey(invitationId, revision),
            serialize(payload), clock.instant());
    }

    public MailDeliveryContent previousInvitation(String enterpriseId, String invitationId) {
        var queued = mail.latestInvitation(enterpriseId, invitationId)
            .orElseThrow(() -> new IllegalStateException("邀请投递记录不存在"));
        var payload = parse(queued.payloadJson());
        if (!payload.purpose().equals("enterprise_invitation") || !payload.targetId().equals(invitationId)) {
            throw new IllegalStateException("邀请投递内容不匹配");
        }
        return encryption.decrypt(payload.content(), queued.id(), MailDeliveryContent.class);
    }

    public MailJobPayload parse(String value) {
        try {
            return json.readValue(value, MailJobPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("邮件投递记录无法读取");
        }
    }

    public String serialize(MailJobPayload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("邮件投递记录无法保存");
        }
    }
}
