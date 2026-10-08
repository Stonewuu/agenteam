package com.stonewu.agenteam.service.integration;

import com.stonewu.agenteam.mapper.auth.AuthMapper;
import com.stonewu.agenteam.mapper.enterprise.EnterpriseMapper;
import com.stonewu.agenteam.mapper.integration.EnterpriseIntegrationMapper;
import com.stonewu.agenteam.model.integration.entity.ChannelOauthSessionRow;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.auth.AuthContextService;
import com.stonewu.agenteam.service.auth.LocalAuthenticationService;
import com.stonewu.agenteam.service.http.ApiException;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 授权开始、回调及确认分别核对最新的企业、应用和本地账号。 */
@Service
public class ChannelAuthorizationPolicy {
    private final EnterpriseIntegrationMapper connections;
    private final EnterpriseMapper enterprises;
    private final AuthMapper users;
    private final AuthContextService identity;
    private final LocalAuthenticationService local;

    public ChannelAuthorizationPolicy(EnterpriseIntegrationMapper connections, EnterpriseMapper enterprises,
                                      AuthMapper users, AuthContextService identity, LocalAuthenticationService local) {
        this.connections = connections;
        this.enterprises = enterprises;
        this.users = users;
        this.identity = identity;
        this.local = local;
    }

    public EnterpriseIntegrationRow connection(String id, String purpose) {
        var row = connections.selectById(id);
        if (row == null || !"enabled".equals(row.getStatus()) || row.getIdentityVerifiedAt() == null
            || row.getExternalTenantId() == null || "bind".equals(purpose) && !Boolean.TRUE.equals(row.getBindingEnabled())
            || "login".equals(purpose) && !Boolean.TRUE.equals(row.getLoginEnabled())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHANNEL_UNAVAILABLE", "此接入暂时无法授权，请联系企业管理员。");
        }
        var enterprise = enterprises.findById(row.getEnterpriseId());
        if (enterprise.isEmpty() || !"active".equals(enterprise.get().status())) {
            throw unavailable();
        }
        return row;
    }

    public EnterpriseIntegrationRow current(ChannelOauthSessionRow flow, HttpSession session, boolean lock) {
        if (lock) {
            enterprises.lockEnterprise(flow.getEnterpriseId());
        }
        var connection = connection(flow.getConnectionId(), flow.getPurpose());
        if (!flow.getEnterpriseId().equals(connection.getEnterpriseId()) || !flow.getConnectionRevision().equals(connection.getRevision())) {
            throw unavailable();
        }
        if ("bind".equals(flow.getPurpose())) {
            var actor = local.requireRecent(session);
            identity.requireEnterprise(session, flow.getEnterpriseId());
            if (!actor.id().equals(flow.getInitiatorUserId()) || actor.sessionVersion() != flow.getInitiatorSessionVersion()) {
                throw unavailable();
            }
        } else if (identity.optionalUser(session) != null) {
            throw unavailable();
        }
        return connection;
    }

    public void member(String enterprise, String user) {
        if (!users.isActiveMember(user, enterprise)) {
            throw unavailable();
        }
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "CHANNEL_AUTHORIZATION_EXPIRED", "本次授权已失效，请重新发起。");
    }
}
