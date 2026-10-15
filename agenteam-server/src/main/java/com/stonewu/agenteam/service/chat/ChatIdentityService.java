package com.stonewu.agenteam.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.integration.UserChannelBindingMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.integration.entity.UserChannelBindingRow;
import com.stonewu.agenteam.service.auth.AccountBehaviorService;
import com.stonewu.agenteam.service.edition.EnterpriseEditionPolicy;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.integration.ChannelIdentityScopeService;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 消息身份只在已认证应用范围内匹配，协作始终使用原发起人的当前权限。 */
@Service
public class ChatIdentityService {
    private final UserChannelBindingMapper bindings;
    private final AuthMapper users;
    private final PermissionMapper permissions;
    private final AccountBehaviorService behavior;
    private final EnterpriseEditionPolicy edition;
    private final EnterpriseAuthorizationService authorization;
    private final EnterpriseIntegrationMapper integrations;
    private final ChannelIdentityScopeService scopes;

    public ChatIdentityService(UserChannelBindingMapper bindings, AuthMapper users, PermissionMapper permissions,
                               AccountBehaviorService behavior, EnterpriseEditionPolicy edition,
                               EnterpriseAuthorizationService authorization, EnterpriseIntegrationMapper integrations, ChannelIdentityScopeService scopes) {
        this.bindings = bindings;
        this.users = users;
        this.permissions = permissions;
        this.behavior = behavior;
        this.edition = edition;
        this.authorization = authorization;
        this.integrations = integrations;
        this.scopes = scopes;
    }

    public Optional<UserChannelBindingRow> find(String enterprise, String connection, String subjectType, String subject) {
        return find(enterprise, connection, subjectType, subject, null);
    }

    public Optional<UserChannelBindingRow> find(String enterprise, String connection, String subjectType, String subject, String unionId) {
        return bound(enterprise, connection, subjectType, subject, unionId).filter(row -> Boolean.TRUE.equals(row.getChatEnabled()));
    }

    public List<String> availableConnections(String enterprise, String connection) {
        var application = integrations.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, connection)
            .eq(EnterpriseIntegrationRow::getStatus, "enabled").isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt));
        return application == null ? List.of() : scopes.connectionIds(application, true);
    }

    public LambdaQueryWrapper<UserChannelBindingRow> candidates(String enterprise, String connection) {
        var available = availableConnections(enterprise, connection);
        return new LambdaQueryWrapper<UserChannelBindingRow>().eq(UserChannelBindingRow::getEnterpriseId, enterprise)
            .in(UserChannelBindingRow::getConnectionId, available.isEmpty() ? List.of("") : available)
            .eq(UserChannelBindingRow::getStatus, "active").eq(UserChannelBindingRow::getChatEnabled, true)
            .and(query -> query.eq(UserChannelBindingRow::getConnectionId, connection).or()
                .isNotNull(UserChannelBindingRow::getExternalUnionId).ne(UserChannelBindingRow::getExternalUnionId, ""));
    }

    private Optional<UserChannelBindingRow> bound(String enterprise, String connection, String subjectType, String subject, String unionId) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        var application = integrations.selectOne(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, connection)
            .eq(EnterpriseIntegrationRow::getStatus, "enabled").isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt));
        if (application == null) {
            return Optional.empty();
        }
        boolean shared = scopes.shared(application) && "feishu_open_id".equals(subjectType) && unionId != null && !unionId.isBlank();
        var rows = bindings.selectList(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getStatus, "active")
            .eq(UserChannelBindingRow::getExternalSubjectType, subjectType).and(query -> {
                query.nested(exact -> exact.eq(UserChannelBindingRow::getConnectionId, connection).eq(UserChannelBindingRow::getExternalSubjectId, subject));
                if (shared) {
                    query.or().nested(same -> same.in(UserChannelBindingRow::getConnectionId, scopes.connectionIds(application, true))
                        .eq(UserChannelBindingRow::getExternalUnionId, unionId));
                }
            }));
        if (rows.stream().map(UserChannelBindingRow::getUserId).distinct().count() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CHANNEL_IDENTITY_CONFLICT", "此平台账号关联了不同群策账号，请在个人设置中检查原绑定。");
        }
        // 同一身份的历史记录只要有一条关闭聊天，就不能通过另一个机器人绕过。
        return rows.stream().min(Comparator.comparing((UserChannelBindingRow row) -> Boolean.TRUE.equals(row.getChatEnabled()))
            .thenComparing(row -> !connection.equals(row.getConnectionId())).thenComparing(UserChannelBindingRow::getId));
    }

    public String bindingNotice(String enterprise, String connection, String subjectType, String subject, String unionId, String platform) {
        String account = "feishu".equals(platform) ? "飞书账号" : "企业微信账号";
        if (bound(enterprise, connection, subjectType, subject, unionId).isPresent()) {
            return "你的" + account + "已绑定，但尚未允许使用智能体。请打开群策「个人设置 → 企业账号绑定」，开启「允许通过此账号使用智能体」，然后重新发送消息。";
        }
        return "还未将你的" + account + "与群策账号绑定。请登录群策，打开「个人设置 → 企业账号绑定」，找到对应平台并点击「授权并绑定」。完成后回到这里重新发送消息。";
    }

    public AuthContext actor(String enterprise, String userId, String bindingId) {
        var binding = bindings.selectOne(new LambdaQueryWrapper<UserChannelBindingRow>()
            .eq(UserChannelBindingRow::getEnterpriseId, enterprise).eq(UserChannelBindingRow::getId, bindingId)
            .eq(UserChannelBindingRow::getUserId, userId).eq(UserChannelBindingRow::getStatus, "active")
            .eq(UserChannelBindingRow::getChatEnabled, true));
        if (binding == null || integrations.selectCount(new LambdaQueryWrapper<EnterpriseIntegrationRow>()
            .eq(EnterpriseIntegrationRow::getEnterpriseId, enterprise).eq(EnterpriseIntegrationRow::getId, binding.getConnectionId())
            .eq(EnterpriseIntegrationRow::getStatus, "enabled").isNotNull(EnterpriseIntegrationRow::getIdentityVerifiedAt)) != 1) {
            throw unavailable();
        }
        return actor(enterprise, userId);
    }

    public AuthContext actor(String enterprise, String userId) {
        var user = users.findById(userId).filter(value -> "active".equals(value.status()))
            .orElseThrow(ChatIdentityService::unavailable);
        if (!behavior.allowed(user) || !behavior.enterpriseAllowed(user, enterprise) || !users.isActiveMember(userId, enterprise)) {
            throw unavailable();
        }
        edition.requireEnterpriseScope(enterprise);
        Set<String> allowed = permissions.listPermissionCodes(userId, enterprise).stream()
            .filter(code -> behavior.permissionAllowed(user, code)).collect(Collectors.toUnmodifiableSet());
        var actor = new AuthContext(user, enterprise, allowed);
        authorization.require(actor, "agent.run");
        return actor;
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_BINDING_REQUIRED", "请先绑定群策账号并开启聊天，或确认原账号仍可使用。");
    }
}
