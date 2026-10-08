package com.stonewu.agenteam.service.integration;

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

    public ChannelBindingService(UserChannelBindingMapper bindings, EnterpriseIntegrationMapper connections,
                                  EnterpriseMapper enterprises, AuthMapper users, AuthContextService identity,
                                  LocalAuthenticationService local, ChannelOauthStore flows,
                                  ChannelAuthorizationSecrets secrets, ChannelAuthorizationPolicy policy,
                                  IntegrationProviderRegistry providers, AuditEventService audit, Clock clock,
                                  ChannelDeliveryCancellation cancellations) {
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
        return choices.stream().map(connection -> view(connection, current.get(connection.getId()))).toList();
    }

    public ChannelAuthorizationReview review(String id, String nonce, String confirmation, HttpSession session) {
        var flow = flows.confirmation(id, nonce, confirmation, session);
        var actor = local.requireRecent(session);
        var connection = policy.current(flow, session, false);
        var external = secrets.decrypt(flow.getId(), flow.getTransientEncryptedJson()).identity();
        return new ChannelAuthorizationReview(flow.getId(), flow.getEnterpriseId(), connection.getName(), providers.require(connection.getProviderCode()).name(),
            actor.username(), actor.displayName(), external.displayName(), external.subjectId(), flow.getConfirmationExpiresAt().toString(),
            !actor.superAdmin() && Boolean.TRUE.equals(connection.getLoginEnabled()));
    }

    public ChannelBindingView confirm(String id, String nonce, String confirmation,
                                       ChannelBindingUpdateRequest input, HttpSession session) {
        requireFlags(input);
        var flow = flows.confirmation(id, nonce, confirmation, session);
        var actor = local.requireRecent(session);
        var connection = policy.current(flow, session, false);
        var external = secrets.decrypt(id, flow.getTransientEncryptedJson()).identity();
        checkIdentity(connection, external);
        if (Boolean.TRUE.equals(input.externalLoginEnabled()) && (actor.superAdmin() || !Boolean.TRUE.equals(connection.getLoginEnabled()))) {
            throw ApiException.invalidField("externalLoginEnabled", "当前账号或企业接入未开放外部登录。");
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
        row.setExternalLoginEnabled(input.externalLoginEnabled());
        row.setAuthorizedAt(clock.instant());
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        row.setRevision(1L);
        flows.consume(flow, "BOUND");
        try {
            bindings.insert(row);
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANNEL_BINDING_CONFLICT", "本账号或外部身份已有绑定，请先解绑原账号。", conflict);
        }
        audit.record(flow.getEnterpriseId(), actor, "channel.bind", "channel_binding", row.getId(), "用户确认绑定外部账号", Map.of("connectionId", connection.getId()));
        return view(connection, row);
    }

    public ChannelBindingView update(String enterprise, String id, long revision, ChannelBindingUpdateRequest input, HttpSession session) {
        requireFlags(input);
        var actor = requireActor(enterprise, session);
        var row = own(enterprise, id, actor.id(), revision);
        var connection = connections.selectById(row.getConnectionId());
        if (Boolean.TRUE.equals(input.externalLoginEnabled()) && (actor.superAdmin() || !"enabled".equals(connection.getStatus())
            || !Boolean.TRUE.equals(connection.getLoginEnabled()))) {
            throw ApiException.invalidField("externalLoginEnabled", "当前账号或企业接入未开放外部登录。");
        }
        if (bindings.update(changes(row).set(UserChannelBindingRow::getReceiveEnabled, input.receiveEnabled())
            .set(UserChannelBindingRow::getExternalLoginEnabled, input.externalLoginEnabled())) != 1) {
            throw ApiException.versionConflict(revision);
        }
        audit.record(enterprise, actor, "channel.binding_update", "channel_binding", id, "更新本人通知和登录选择", Map.of());
        cancellations.cancel(enterprise, row.getConnectionId(), row.getId(), actor.id(), null);
        return view(connection, bindings.selectById(id));
    }

    public void revoke(String enterprise, String id, long revision, HttpSession session) {
        var actor = requireActor(enterprise, session);
        var row = own(enterprise, id, actor.id(), revision);
        if (bindings.update(changes(row).set(UserChannelBindingRow::getStatus, "revoked")
            .set(UserChannelBindingRow::getRevokedAt, clock.instant()).set(UserChannelBindingRow::getReceiveEnabled, false)
            .set(UserChannelBindingRow::getExternalLoginEnabled, false)) != 1) {
            throw ApiException.versionConflict(revision);
        }
        audit.record(enterprise, actor, "channel.unbind", "channel_binding", id, "用户解除外部账号绑定", Map.of());
        cancellations.cancel(enterprise, row.getConnectionId(), row.getId(), actor.id(), null);
    }

    public LoginResult login(String id, String nonce, ChannelIdentity external, HttpSession session) {
        var flow = flows.require(id, "processing", nonce, session);
        var connection = policy.current(flow, session, false);
        checkIdentity(connection, external);
        var binding = bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, flow.getEnterpriseId()).eq(UserChannelBindingRow::getConnectionId, connection.getId())
            .eq(UserChannelBindingRow::getExternalSubjectType, external.subjectType()).eq(UserChannelBindingRow::getExternalSubjectId, external.subjectId())
            .eq(UserChannelBindingRow::getStatus, "active").eq(UserChannelBindingRow::getExternalLoginEnabled, true));
        var user = binding == null ? null : users.findById(binding.getUserId()).orElse(null);
        if (user == null || !"active".equals(user.status()) || user.superAdmin() || !identity.userAllowed(user)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_LOGIN_UNAVAILABLE", "请先使用已有账号登录，绑定外部账号并开启登录。");
        }
        policy.member(flow.getEnterpriseId(), user.id());
        if (bindings.update(new LambdaUpdateWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getId, binding.getId())
            .eq(UserChannelBindingRow::getEnterpriseId, flow.getEnterpriseId()).eq(UserChannelBindingRow::getRevision, binding.getRevision())
            .eq(UserChannelBindingRow::getStatus, "active").eq(UserChannelBindingRow::getExternalLoginEnabled, true)
            .set(UserChannelBindingRow::getLastAuthenticatedAt, clock.instant())) != 1) {
            throw ChannelAuthorizationPolicy.unavailable();
        }
        flows.consume(flow, "LOGGED_IN");
        audit.record(flow.getEnterpriseId(), user, "channel.login", "channel_binding", binding.getId(), "通过外部账号登录企业", Map.of());
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
            throw new ApiException(HttpStatus.NOT_FOUND, "CHANNEL_BINDING_UNAVAILABLE", "绑定不存在或已解除。");
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
            throw ApiException.invalidField("receiveEnabled", "请选择通知接收与外部登录设置。");
        }
    }

    private ChannelBindingView view(EnterpriseIntegrationRow connection, UserChannelBindingRow row) {
        boolean enabled = "enabled".equals(connection.getStatus());
        return new ChannelBindingView(row == null ? null : row.getId(), connection.getId(), connection.getName(), connection.getProviderCode(),
            providers.require(connection.getProviderCode()).name(), row == null ? "unbound" : row.getStatus(), row == null ? null : row.getDisplayName(),
            row != null && Boolean.TRUE.equals(row.getReceiveEnabled()), row != null && Boolean.TRUE.equals(row.getExternalLoginEnabled()),
            row == null ? null : Long.toString(row.getRevision()), enabled && Boolean.TRUE.equals(connection.getBindingEnabled()),
            enabled && Boolean.TRUE.equals(connection.getLoginEnabled()), enabled && Boolean.TRUE.equals(connection.getMessagingEnabled()));
    }

    public record LoginResult(UserEntity user, UserChannelBindingRow binding, long connectionRevision) {
    }
}
