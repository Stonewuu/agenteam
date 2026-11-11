package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.service.i18n.Messages;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelIdentity;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.model.integration.request.ChannelBindingUpdateRequest;
import com.stonewu.agenteam.model.integration.response.ChannelAuthorizationReview;
import com.stonewu.agenteam.model.integration.response.ChannelBindingView;
import com.stonewu.agenteam.model.user.entity.UserEntity;
import com.stonewu.agenteam.service.audit.AuditEventService;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.notification.ChannelDeliveryCancellation;
import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 用户确认后才写入绑定；解绑保留历史记录，重新绑定必须创建新编号。 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChannelBindingService {
    private final UserChannelBindingMapper bindings;
    private final EnterpriseIntegrationMapper connections;
    private final EnterpriseMapper enterprises;
    private final AuthMapper users;
    private final AuthContextService identity;
    private final LocalAuthenticationService local;
    private final ChannelOauthStore flows;
    private final ChannelAuthorizationSecrets secrets;
    private final ChannelAuthorizationPolicy policy;
    private final IntegrationProviderRegistry providers;
    private final AuditEventService audit;
    private final Clock clock;
    private final ChannelDeliveryCancellation cancellations;
    private final ChannelIdentityScopeService scopes;
    private final ChannelBindingRevocationService revocations;

    public ChannelBindingService(UserChannelBindingMapper bindings, EnterpriseIntegrationMapper connections,
                                  EnterpriseMapper enterprises, AuthMapper users, AuthContextService identity,
                                  LocalAuthenticationService local, ChannelOauthStore flows,
                                  ChannelAuthorizationSecrets secrets, ChannelAuthorizationPolicy policy,
                                  IntegrationProviderRegistry providers, AuditEventService audit, Clock clock,
                                  ChannelDeliveryCancellation cancellations, ChannelIdentityScopeService scopes,
                                  ChannelBindingRevocationService revocations) {
        this.bindings = bindings;
        this.connections = connections;
        this.enterprises = enterprises;
        this.users = users;
        this.identity = identity;
        this.local = local;
        this.flows = flows;
        this.secrets = secrets;
        this.policy = policy;
        this.providers = providers;
        this.audit = audit;
        this.clock = clock;
        this.cancellations = cancellations;
        this.scopes = scopes;
        this.revocations = revocations;
    }

    @Transactional(readOnly = true)
    public List<ChannelBindingView> list(String enterprise, HttpSession session) {
        var actor = identity.requireEnterprise(session, enterprise);
        var current = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getUserId, actor.userId())
            .ne(UserChannelBindingRow::getStatus, "revoked")).stream()
            .collect(Collectors.toMap(UserChannelBindingRow::getConnectionId, Function.identity()));
        var choices = connections.selectList(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise)
            .and(group -> {
                group.nested(enabled -> enabled.eq(EnterpriseIntegrationRow::getStatus, "enabled")
                    .eq(EnterpriseIntegrationRow::getBindingEnabled, true));
                if (!current.isEmpty()) {
                    group.or().in(EnterpriseIntegrationRow::getId, current.keySet());
                }
            }).orderByAsc(EnterpriseIntegrationRow::getName, EnterpriseIntegrationRow::getId));
        return scopes.choices(choices, current).stream().map(connection -> view(connection, current.get(connection.getId()))).toList();
    }

    public ChannelAuthorizationReview review(String id, String nonce, String confirmation, HttpSession session) {
        var flow = flows.confirmation(id, nonce, confirmation, session);
        var actor = local.requireRecent(session);
        var connection = policy.current(flow, session, false);
        var external = secrets.decrypt(flow.getId(), flow.getTransientEncryptedJson()).identity();
        checkIdentity(connection, external);
        return new ChannelAuthorizationReview(flow.getId(), flow.getEnterpriseId(), connection.getName(), providers.require(connection.getProviderCode()).name(),
            actor.username(), actor.displayName(), external.displayName(), external.subjectId(), flow.getConfirmationExpiresAt().toString(),
            Boolean.TRUE.equals(connection.getLoginEnabled()), Boolean.TRUE.equals(connection.getMessagingEnabled()),
            Set.of("feishu", "wecom").contains(connection.getProviderCode()));
    }

    public ChannelBindingView confirm(String id, String nonce, String confirmation,
                                       ChannelBindingUpdateRequest input, HttpSession session) {
        requireFlags(input);
        var flow = flows.confirmation(id, nonce, confirmation, session);
        var actor = local.requireRecent(session);
        var connection = policy.current(flow, session, false);
        var external = secrets.decrypt(id, flow.getTransientEncryptedJson()).identity();
        checkIdentity(connection, external);
        enterprises.lockEnterprise(flow.getEnterpriseId()).orElseThrow();
        if (scopes.ownedByAnotherUser(connection, "wecom".equals(connection.getProviderCode()) ? external.subjectId() : external.unionId(), actor.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANNEL_BINDING_CONFLICT", Messages.text("error.integration.channelBindingService.confirm.1"));
        }
        if (Boolean.TRUE.equals(input.externalLoginEnabled()) && !Boolean.TRUE.equals(connection.getLoginEnabled())) {
            throw ApiException.invalidField("externalLoginEnabled", Messages.text("error.integration.channelBindingService.confirm.2"));
        }
        var row = new UserChannelBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setEnterpriseId(flow.getEnterpriseId());
        row.setConnectionId(connection.getId());
        row.setUserId(actor.id());
        row.setExternalSubjectType(external.subjectType());
        row.setExternalSubjectId(external.subjectId());
        row.setExternalUnionId(external.unionId());
        row.setDisplayName(external.displayName());
        row.setStatus("active");
        row.setReceiveEnabled(input.receiveEnabled());
        row.setChatEnabled(Boolean.TRUE.equals(input.chatEnabled()));
        row.setExternalLoginEnabled(input.externalLoginEnabled());
        row.setAuthorizedAt(clock.instant());
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        row.setRevision(1L);
        flows.consume(flow, "BOUND");
        try {
            bindings.insert(row);
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANNEL_BINDING_CONFLICT", Messages.text("error.integration.channelBindingService.confirm.3"), conflict);
        }
        updateSharedChat(connection, row, input.chatEnabled());
        audit.record(flow.getEnterpriseId(), actor, "channel.bind", "channel_binding", row.getId(), Messages.text("text.integration.channelBindingService.confirm.1"), Map.of("connectionId", connection.getId()));
        return view(connection, row);
    }

    public ChannelBindingView update(String enterprise, String id, long revision, ChannelBindingUpdateRequest input, HttpSession session) {
        requireFlags(input);
        var actor = requireActor(enterprise, session);
        var row = own(enterprise, id, actor.id(), revision);
        var connection = connections.selectById(row.getConnectionId());
        if (connection == null || !"enabled".equals(connection.getStatus()) || !"active".equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANNEL_BINDING_UNAVAILABLE", Messages.text("error.integration.channelBindingService.update.1"));
        }
        if ("bot".equals(connection.getIdentityScope()) && (Boolean.TRUE.equals(input.receiveEnabled()) || Boolean.TRUE.equals(input.externalLoginEnabled()))) {
            throw ApiException.invalidField("receiveEnabled", Messages.text("error.integration.channelBindingService.update.2"));
        }
        if (Boolean.TRUE.equals(input.externalLoginEnabled()) && (!"enabled".equals(connection.getStatus())
            || !Boolean.TRUE.equals(connection.getLoginEnabled()))) {
            throw ApiException.invalidField("externalLoginEnabled", Messages.text("error.integration.channelBindingService.confirm.2"));
        }
        if (bindings.update(changes(row).set(UserChannelBindingRow::getReceiveEnabled, input.receiveEnabled())
            .set(input.chatEnabled() != null, UserChannelBindingRow::getChatEnabled, input.chatEnabled())
            .set(UserChannelBindingRow::getExternalLoginEnabled, input.externalLoginEnabled())) != 1) {
            throw ApiException.versionConflict(revision);
        }
        audit.record(enterprise, actor, "channel.binding_update", "channel_binding", id, Messages.text("text.integration.channelBindingService.update.1"), Map.of());
        updateSharedChat(connection, row, input.chatEnabled());
        cancellations.cancel(enterprise, row.getConnectionId(), row.getId(), actor.id(), null);
        return view(connection, bindings.selectById(id));
    }

    public void revoke(String enterprise, String id, long revision, HttpSession session) {
        var actor = requireActor(enterprise, session);
        var row = own(enterprise, id, actor.id(), revision);
        var connection = connections.selectById(row.getConnectionId());
        for (var related : scopes.related(connection, row)) {
            revocations.revoke(related);
        }
        audit.record(enterprise, actor, "channel.unbind", "channel_binding", id, Messages.text("text.integration.channelBindingService.revoke.1"), Map.of());
    }

    public LoginResult login(String id, String nonce, ChannelIdentity external, HttpSession session) {
        var flow = flows.require(id, "processing", nonce, session);
        var connection = policy.current(flow, session, false);
        checkIdentity(connection, external);
        var binding = bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, flow.getEnterpriseId()).eq(UserChannelBindingRow::getConnectionId, connection.getId())
            .eq(UserChannelBindingRow::getExternalSubjectType, external.subjectType()).eq(UserChannelBindingRow::getExternalSubjectId, external.subjectId())
            .eq(UserChannelBindingRow::getStatus, "active"));
        var user = binding == null ? null : users.findById(binding.getUserId()).orElse(null);
        if (user == null || !"active".equals(user.status()) || !identity.userAllowed(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_LOGIN_UNAVAILABLE", Messages.text("error.integration.channelBindingService.login.1"));
        }
        if (!Boolean.TRUE.equals(binding.getExternalLoginEnabled())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_LOGIN_NOT_ENABLED", Messages.text("error.integration.channelBindingService.login.2"));
        }
        policy.member(flow.getEnterpriseId(), user.id());
        if (bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, binding.getId())
            .eq(UserChannelBindingRow::getEnterpriseId, flow.getEnterpriseId()).eq(UserChannelBindingRow::getRevision, binding.getRevision())
            .eq(UserChannelBindingRow::getStatus, "active").eq(UserChannelBindingRow::getExternalLoginEnabled, true)
            .set(UserChannelBindingRow::getLastAuthenticatedAt, clock.instant())) != 1) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        flows.consume(flow, "LOGGED_IN");
        audit.record(flow.getEnterpriseId(), user, "channel.login", "channel_binding", binding.getId(), Messages.text("text.integration.channelBindingService.login.1"), Map.of());
        return new LoginResult(user, binding, connection.getRevision());
    }

    private UserEntity requireActor(String enterprise, HttpSession session) {
        enterprises.lockEnterprise(enterprise);
        var actor = local.requireRecent(session);
        identity.requireEnterprise(session, enterprise);
        return actor;
    }

    private UserChannelBindingRow own(String enterprise, String id, String user, long revision) {
        var row = bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)
            .eq(UserChannelBindingRow::getId, id).eq(UserChannelBindingRow::getUserId, user).ne(UserChannelBindingRow::getStatus, "revoked"));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHANNEL_BINDING_UNAVAILABLE", Messages.text("error.integration.channelBindingService.own.1"));
        }
        if (row.getRevision() != revision) {
            throw ApiException.versionConflict(row.getRevision());
        }
        return row;
    }

    private LambdaUpdateWrapper<UserChannelBindingRow> changes(UserChannelBindingRow row) {
        return new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, row.getEnterpriseId())
            .eq(UserChannelBindingRow::getId, row.getId()).eq(UserChannelBindingRow::getUserId, row.getUserId())
            .ne(UserChannelBindingRow::getStatus, "revoked").eq(UserChannelBindingRow::getRevision, row.getRevision())
            .set(UserChannelBindingRow::getRevision, row.getRevision() + 1).set(UserChannelBindingRow::getUpdatedAt, clock.instant());
    }

    private void checkIdentity(EnterpriseIntegrationRow connection, ChannelIdentity external) {
        if (external == null || !connection.getExternalTenantId().equals(external.tenantId())) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
    }

    private void requireFlags(ChannelBindingUpdateRequest input) {
        if (input == null || input.receiveEnabled() == null || input.externalLoginEnabled() == null) {
            throw ApiException.invalidField("receiveEnabled", Messages.text("error.integration.channelBindingService.requireFlags.1"));
        }
    }

    private ChannelBindingView view(EnterpriseIntegrationRow connection, UserChannelBindingRow row) {
        boolean enabled = "enabled".equals(connection.getStatus());
        return new ChannelBindingView(row == null ? null : row.getId(), connection.getId(), scopes.shared(connection)
            ? "wecom".equals(connection.getProviderCode()) ? Messages.text("text.chat.chatIdentityService.bindingNotice.2") : Messages.text("text.chat.chatIdentityService.bindingNotice.1") : connection.getName(), connection.getProviderCode(),
            providers.require(connection.getProviderCode()).name(), row == null ? "unbound" : enabled ? row.getStatus() : "disabled", row == null ? null : row.getDisplayName(),
            row != null && Boolean.TRUE.equals(row.getReceiveEnabled()), row != null && Boolean.TRUE.equals(row.getExternalLoginEnabled()),
            row == null ? null : Long.toString(row.getRevision()), enabled && Boolean.TRUE.equals(connection.getBindingEnabled()),
            enabled && Boolean.TRUE.equals(connection.getLoginEnabled()), enabled && Boolean.TRUE.equals(connection.getMessagingEnabled()),
            row != null && Boolean.TRUE.equals(row.getChatEnabled()));
    }

    private void updateSharedChat(EnterpriseIntegrationRow connection, UserChannelBindingRow row, Boolean enabled) {
        if (enabled == null) {
            return;
        }
        for (var related : scopes.related(connection, row)) {
            if (!related.getId().equals(row.getId())) {
                if (bindings.update(changes(related).set(UserChannelBindingRow::getChatEnabled, enabled)) != 1) {
                    throw ApiException.versionConflict(related.getRevision());
                }
            }
        }
    }

    public record LoginResult(UserEntity user, UserChannelBindingRow binding, long connectionRevision) {
    }
}
