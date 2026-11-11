package com.stonewu.agenteam.service.chat;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.ChannelOauthSessionMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.chat.entity.NormalizedChatEvent;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.IntegrationApplication;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelAuthorizationSecrets;
import com.stonewu.agenteam.service.integration.ChannelIdentityScopeService;
import com.stonewu.agenteam.service.integration.wecom.WecomEnterpriseConnections;
import com.stonewu.agenteam.service.integration.wecom.WecomMemberAliases;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** 成员通过本企业任意已核验机器人证明身份，绑定保存到企业接入，同企业机器人共同使用。 */
@Service
public class ChatPairingService {
    private final ChannelOauthSessionMapper flows;
    private final UserChannelBindingMapper bindings;
    private final EnterpriseMapper enterprises;
    private final AuthContextService auth;
    private final LocalAuthenticationService local;
    private final ChatIdentityService identities;
    private final ChannelAuthorizationSecrets secrets;
    private final AuditEventService audit;
    private final Clock clock;
    private final WecomEnterpriseConnections wecomEnterprises;
    private final WecomMemberAliases wecomMembers;
    private final ChannelIdentityScopeService scopes;

    public ChatPairingService(ChannelOauthSessionMapper flows, UserChannelBindingMapper bindings, EnterpriseMapper enterprises,
            AuthContextService auth, LocalAuthenticationService local,
            ChatIdentityService identities, ChannelAuthorizationSecrets secrets, AuditEventService audit, Clock clock,
            WecomEnterpriseConnections wecomEnterprises, WecomMemberAliases wecomMembers, ChannelIdentityScopeService scopes) {
        this.flows = flows;
        this.bindings = bindings;
        this.enterprises = enterprises;
        this.auth = auth;
        this.local = local;
        this.identities = identities;
        this.secrets = secrets;
        this.audit = audit;
        this.clock = clock;
        this.wecomEnterprises = wecomEnterprises;
        this.wecomMembers = wecomMembers;
        this.scopes = scopes;
    }

    @Transactional
    public Map<String, String> create(String enterprise, String connection, HttpSession session) {
        var actor = auth.requirePermission(session, enterprise, "agent.run");
        var user = local.requireRecent(session);
        enterprises.lockEnterprise(enterprise).orElseThrow();
        identities.actor(enterprise, actor.userId());
        var app = wecomEnterprises.require(enterprise, connection);
        if (!user.id().equals(actor.userId()) || !Boolean.TRUE.equals(app.getBindingEnabled())) {
            throw ChatPlatformRegistry.unavailable();
        }
        if (flows.selectCount(new LambdaQueryWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getInitiatorUserId, user.id()).eq(ChannelOauthSessionRow::getPurpose, "chat_pairing")
            .gt(ChannelOauthSessionRow::getCreatedAt, clock.instant().minusSeconds(60))) >= 5) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_PAIRING_RATE_LIMITED", Messages.text("error.chat.chatPairingService.create.1"));
        }
        flows.update(new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getConnectionId, connection).eq(ChannelOauthSessionRow::getInitiatorUserId, user.id())
            .eq(ChannelOauthSessionRow::getPurpose, "chat_pairing").eq(ChannelOauthSessionRow::getStatus, "pending")
            .set(ChannelOauthSessionRow::getStatus, "expired").set(ChannelOauthSessionRow::getUpdatedAt, clock.instant()));
        var flow = new ChannelOauthSessionRow();
        flow.setId(UUID.randomUUID().toString());
        String code = flow.getId() + "." + secrets.random();
        flow.setEnterpriseId(enterprise);
        flow.setConnectionId(connection);
        flow.setPurpose("chat_pairing");
        flow.setInitiatorUserId(user.id());
        flow.setInitiatorSessionVersion(user.sessionVersion());
        flow.setConnectionRevision(app.getRevision());
        flow.setStateHash(secrets.hash(code));
        flow.setBrowserNonceHash(secrets.hash(secrets.random()));
        flow.setReturnPath("/enterprises/" + enterprise + "/settings/channels");
        flow.setStatus("pending");
        flow.setPairingAttempts(0);
        flow.setExpiresAt(clock.instant().plusSeconds(300));
        flow.setCreatedAt(clock.instant());
        flow.setUpdatedAt(clock.instant());
        flows.insert(flow);
        audit.record(enterprise, user, "chat.pairing_create", "integration", connection, Messages.text("text.chat.chatPairingService.create.1"), Map.of());
        return Map.of("code", code, "expiresAt", flow.getExpiresAt().toString(), "command", Messages.source("protocol.chat.chatPairingService.create.1", code));
    }

    public boolean command(IntegrationApplication app, NormalizedChatEvent event) {
        return app.providerCode().equals("wecom_aibot") && event.humanMessage()
            && (event.text().strip().startsWith(Messages.source("protocol.chat.chatPairingService.command.1")) || event.text().strip().startsWith(Messages.source("protocol.chat.chatPairingService.command.2")));
    }

    @Transactional
    public String consume(IntegrationApplication application, NormalizedChatEvent event) {
        enterprises.lockEnterprise(application.enterpriseId()).orElseThrow();
        if (!event.chatKind().equals("direct")) {
            return Messages.text("text.chat.chatPairingService.consume.1");
        }
        if (!event.text().strip().startsWith(Messages.source("protocol.chat.chatPairingService.command.1"))) {
            return Messages.text("text.chat.chatPairingService.consume.2");
        }
        var verified = wecomEnterprises.verified(application.enterpriseId(), application.id());
        var member = wecomMembers.find(application.enterpriseId(), application.id(), event.subjectType(), event.senderId());
        if (verified.isEmpty() || member.isEmpty()) {
            return Messages.text("text.chat.chatPairingService.consume.3");
        }
        var app = verified.get().enterprise();
        if (!Boolean.TRUE.equals(app.getBindingEnabled())) {
            return Messages.text("text.chat.chatPairingService.consume.4");
        }
        String subject = member.get().subjectId();
        String code = event.text().strip().substring(Messages.source("protocol.chat.chatPairingService.command.1").length()).strip();
        String[] parts = code.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("[a-f0-9-]{36}") || parts[1].length() > 128) {
            return rejected();
        }
        var flow = flows.selectOne(new LambdaQueryWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getEnterpriseId, application.enterpriseId()).eq(ChannelOauthSessionRow::getId, parts[0])
            .eq(ChannelOauthSessionRow::getConnectionId, app.getId()).eq(ChannelOauthSessionRow::getPurpose, "chat_pairing"));
        if (flow == null || !flow.getStatus().equals("pending") || !flow.getExpiresAt().isAfter(clock.instant())) {
            return rejected();
        }
        if (!secrets.matches(code, flow.getStateHash())) {
            int attempts = Math.min(5, flow.getPairingAttempts() + 1);
            flows.update(update(flow).set(ChannelOauthSessionRow::getPairingAttempts, attempts)
                .set(attempts == 5, ChannelOauthSessionRow::getStatus, "failed"));
            return rejected();
        }
        if (flow.getConnectionRevision() != app.getRevision() || !app.getStatus().equals("enabled")) {
            flows.update(update(flow).set(ChannelOauthSessionRow::getStatus, "failed"));
            return rejected();
        }
        var actor = identities.actor(application.enterpriseId(), flow.getInitiatorUserId());
        if (actor.user().sessionVersion() != flow.getInitiatorSessionVersion()) {
            flows.update(update(flow).set(ChannelOauthSessionRow::getStatus, "failed"));
            return rejected();
        }
        boolean conflict = bindings.exists(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, application.enterpriseId()).in(UserChannelBindingRow::getConnectionId, scopes.connectionIds(app, false))
            .ne(UserChannelBindingRow::getStatus, "revoked").and(value -> value.eq(UserChannelBindingRow::getUserId, actor.userId())
                .or().eq(UserChannelBindingRow::getExternalSubjectId, subject)));
        if (conflict) {
            flows.update(update(flow).set(ChannelOauthSessionRow::getStatus, "failed"));
            return Messages.text("text.chat.chatPairingService.consume.5");
        }
        var row = new UserChannelBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(application.enterpriseId());
        row.setConnectionId(app.getId());
        row.setUserId(actor.userId());
        row.setExternalSubjectType("wecom_userid");
        row.setExternalSubjectId(subject);
        row.setStatus("active");
        row.setReceiveEnabled(false);
        row.setChatEnabled(true);
        row.setExternalLoginEnabled(false);
        row.setAuthorizedAt(clock.instant());
        row.setRevision(1L);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        bindings.insert(row);
        if (flows.update(update(flow).set(ChannelOauthSessionRow::getStatus, "consumed")
            .set(ChannelOauthSessionRow::getConsumedAt, clock.instant()).set(ChannelOauthSessionRow::getResultCode, "CHAT_BOUND")) != 1) {
            throw new IllegalStateException(Messages.text("error.chat.chatPairingService.consume.1"));
        }
        audit.record(application.enterpriseId(), actor.user(), "chat.paired", "channel_binding", row.getId(), Messages.text("text.chat.chatPairingService.consume.6"), Map.of("connectionId", app.getId()));
        return Messages.text("text.chat.chatPairingService.consume.7");
    }

    private LambdaUpdateWrapper<ChannelOauthSessionRow> update(ChannelOauthSessionRow flow) {
        return new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, flow.getEnterpriseId())
            .eq(ChannelOauthSessionRow::getId, flow.getId()).eq(ChannelOauthSessionRow::getStatus, "pending")
            .set(ChannelOauthSessionRow::getUpdatedAt, clock.instant());
    }

    private String rejected() {
        return Messages.text("error.chat.chatPairingService.rejected.1");
    }
}
