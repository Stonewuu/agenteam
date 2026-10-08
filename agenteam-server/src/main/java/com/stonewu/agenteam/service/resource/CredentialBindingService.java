package com.stonewu.agenteam.service.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.mapper.plugin.PluginConfigurationMapper;
import com.stonewu.agenteam.mapper.security.CredentialMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.resource.entity.ResourceKind;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 绑定新凭据或修改连接目的地必须具有凭据使用权限，查看凭据不允许绑定。
 */
@Service
public class CredentialBindingService {
    private final PermissionMapper permissions;
    private final EnterpriseAuthorizationService authorization;
    private final CredentialMapper credentials;

    public CredentialBindingService(PermissionMapper permissions, EnterpriseAuthorizationService authorization,
                                    CredentialMapper credentials) {
        this.permissions = permissions;
        this.authorization = authorization;
        this.credentials = credentials;
    }

    public boolean canBind(AuthContext actor) {
        return permissions.operationScope(actor.userId(), actor.enterpriseId(), "credential.use").isPresent()
            || permissions.operationScope(actor.userId(), actor.enterpriseId(), "credential.manage").isPresent();
    }

    public void validate(AuthContext actor, ResourceKind kind, JsonNode previous, JsonNode next) {
        if (kind != ResourceKind.PLUGIN && kind != ResourceKind.DATA) {
            return;
        }
        if (kind == ResourceKind.PLUGIN) {
            previous = previous == null ? null : PluginConfigurationMapper.connection(previous);
            next = PluginConfigurationMapper.connection(next);
        }
        JsonNode id = next.path("credentialId");
        if (id.isNull() || id.isMissingNode()) {
            return;
        }
        boolean changed = previous == null || !id.equals(previous.path("credentialId")) || !sameDestination(kind,
            previous, next);
        if (!changed) {
            return;
        }
        authorization.require(actor,
            actor.permissions().contains("credential.use") ? "credential.use" : "credential.manage");
        if (credentials.find(actor.enterpriseId(), id.asText()).filter(value -> value.status().equals("active"))
            .isEmpty()) {
            throw ApiException.invalidField("config.credentialId", "请选择本企业当前有效的凭据。");
        }
    }

    private boolean sameDestination(ResourceKind kind, JsonNode previous, JsonNode next) {
        if (kind == ResourceKind.DATA) {
            return Objects.equals(previous.get("sourceType"), next.get("sourceType")) && Objects.equals(
                previous.get("connection"), next.get("connection"));
        }
        return Objects.equals(previous.get("pluginType"), next.get("pluginType")) && Objects.equals(
            previous.get("transport"), next.get("transport")) && Objects.equals(previous.get("endpoint"),
            next.get("endpoint"));
    }
}
