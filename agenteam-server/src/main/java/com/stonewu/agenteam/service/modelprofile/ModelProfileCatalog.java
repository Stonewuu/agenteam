package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.response.ModelProfileView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模型选择和实际使用都读取当前管理配置，不缓存已停用的模型或提供方。
 */
@Service
public class ModelProfileCatalog {
    private final ModelProfileMapper profiles;
    private final PermissionMapper permissions;
    private final EnterpriseAuthorizationService authorization;

    public ModelProfileCatalog(ModelProfileMapper profiles, PermissionMapper permissions,
                               EnterpriseAuthorizationService authorization) {
        this.profiles = profiles;
        this.permissions = permissions;
        this.authorization = authorization;
    }

    public List<ModelProfileView> list(AuthContext actor) {
        String operation = List.of("model.manage", "agent.run", "agent.view", "agent.create", "agent.edit",
                "agent.preview", "workflow.view", "workflow.create", "workflow.edit", "workflow.preview").stream()
            .filter(code -> permissions.operationScope(actor.userId(), actor.enterpriseId(), code).isPresent())
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号不能查看模型选项。"));
        authorization.require(actor, operation);
        return profiles.list(actor.enterpriseId()).stream().map(
            value -> new ModelProfileView(value.id(), value.name(), value.modelName(), available(value),
                value.capabilities())).toList();
    }

    public ModelProfileRecord requireAvailable(String enterprise, String id) {
        return profiles.find(enterprise, id).filter(this::available)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "MODEL_UNAVAILABLE",
                "所选模型已停用或不可使用，请调整配置。"));
    }

    private boolean available(ModelProfileRecord profile) {
        return profile.enabled() && profile.providerEnabled();
    }
}
