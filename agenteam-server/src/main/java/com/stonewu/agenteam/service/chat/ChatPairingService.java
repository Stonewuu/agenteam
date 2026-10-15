package com.stonewu.agenteam.service.chat;

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
import com.stonewu.agenteam.service.integration.IntegrationQueryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** 成员先在本地登录并确认聊天，再用一次性随机码证明机器人私聊身份。 */
@Service
public class ChatPairingService {
    private final ChannelOauthSessionMapper flows;
    private final UserChannelBindingMapper bindings;
    private final EnterpriseMapper enterprises;
    private final IntegrationQueryService integrations;
    private final AuthContextService auth;
    private final LocalAuthenticationService local;
    private final ChatIdentityService identities;
    private final ChannelAuthorizationSecrets secrets;
    private final AuditEventService audit;
    private final Clock clock;

    public ChatPairingService(ChannelOauthSessionMapper flows, UserChannelBindingMapper bindings, EnterpriseMapper enterprises,
            IntegrationQueryService integrations, AuthContextService auth, LocalAuthenticationService local,
            ChatIdentityService identities, ChannelAuthorizationSecrets secrets, AuditEventService audit, Clock clock) {
        this.flows = flows;
        this.bindings = bindings;
        this.enterprises = enterprises;
        this.integrations = integrations;
        this.auth = auth;
        this.local = local;
        this.identities = identities;
        this.secrets = secrets;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Map<String, String> create(String enterprise, String connection, HttpSession session) {
        var actor = auth.requirePermission(session, enterprise, "agent.run");
        var user = local.requireRecent(session);
        enterprises.lockEnterprise(enterprise).orElseThrow();
        identities.actor(enterprise, actor.userId());
        var app = integrations.require(enterprise, connection);
        if (!user.id().equals(actor.userId()) || !app.getProviderCode().equals("wecom_aibot")
            || !app.getStatus().equals("enabled") || app.getIdentityVerifiedAt() == null) {
            throw ChatPlatformRegistry.unavailable();
        }
        if (flows.selectCount(new LambdaQueryWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, enterprise)
            .eq(ChannelOauthSessionRow::getInitiatorUserId, user.id()).eq(ChannelOauthSessionRow::getPurpose, "chat_pairing")
            .gt(ChannelOauthSessionRow::getCreatedAt, clock.instant().minusSeconds(60))) >= 5) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CHAT_PAIRING_RATE_LIMITED", "配对码申请过于频繁，请一分钟后重试。");
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
        audit.record(enterprise, user, "chat.pairing_create", "integration", connection, "申请企微智能机器人私聊配对", Map.of());
        return Map.of("code", code, "expiresAt", flow.getExpiresAt().toString(), "command", "绑定 " + code);
    }

    public boolean command(IntegrationApplication app, NormalizedChatEvent event) {
        return app.providerCode().equals("wecom_aibot") && event.humanMessage() && event.text().strip().startsWith("绑定 ");
    }

    @Transactional
    public String consume(IntegrationApplication application, NormalizedChatEvent event) {
        enterprises.lockEnterprise(application.enterpriseId()).orElseThrow();
        if (!event.chatKind().equals("direct")) {
            return "请在机器人私聊中完成账号配对；群聊中不会建立绑定。";
        }
        String code = event.text().strip().substring(3).strip();
        String[] parts = code.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("[a-f0-9-]{36}") || parts[1].length() > 128) {
            return rejected();
        }
        var flow = flows.selectOne(new LambdaQueryWrapper<ChannelOauthSessionRow>()
            .eq(ChannelOauthSessionRow::getEnterpriseId, application.enterpriseId()).eq(ChannelOauthSessionRow::getId, parts[0])
            .eq(ChannelOauthSessionRow::getConnectionId, application.id()).eq(ChannelOauthSessionRow::getPurpose, "chat_pairing"));
        if (flow == null || !flow.getStatus().equals("pending") || !flow.getExpiresAt().isAfter(clock.instant())) {
            return rejected();
        }
        var app = integrations.require(application.enterpriseId(), application.id());
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
            .eq(UserChannelBindingRow::getEnterpriseId, application.enterpriseId()).eq(UserChannelBindingRow::getConnectionId, application.id())
            .ne(UserChannelBindingRow::getStatus, "revoked").and(value -> value.eq(UserChannelBindingRow::getUserId, actor.userId())
                .or().eq(UserChannelBindingRow::getExternalSubjectId, event.senderId())));
        if (conflict) {
            flows.update(update(flow).set(ChannelOauthSessionRow::getStatus, "failed"));
            return "群策账号或此企微身份已有绑定，请先在群策处理原绑定后重新配对。";
        }
        var row = new UserChannelBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(application.enterpriseId());
        row.setConnectionId(application.id());
        row.setUserId(actor.userId());
        row.setExternalSubjectType("wecom_aibot_userid");
        row.setExternalSubjectId(event.senderId());
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
            throw new IllegalStateException("聊天配对码消费失败");
        }
        audit.record(application.enterpriseId(), actor.user(), "chat.paired", "channel_binding", row.getId(), "成员在企微私聊完成聊天配对", Map.of("connectionId", application.id()));
        return "群策账号绑定成功，已开启此机器人的聊天。";
    }

    private LambdaUpdateWrapper<ChannelOauthSessionRow> update(ChannelOauthSessionRow flow) {
        return new LambdaUpdateWrapper<ChannelOauthSessionRow>().eq(ChannelOauthSessionRow::getEnterpriseId, flow.getEnterpriseId())
            .eq(ChannelOauthSessionRow::getId, flow.getId()).eq(ChannelOauthSessionRow::getStatus, "pending")
            .set(ChannelOauthSessionRow::getUpdatedAt, clock.instant());
    }

    private String rejected() {
        return "配对码不可用，请在群策重新申请并在五分钟内发到此机器人的私聊。";
    }
}
