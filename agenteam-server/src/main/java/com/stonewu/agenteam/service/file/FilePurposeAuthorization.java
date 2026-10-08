package com.stonewu.agenteam.service.file;

import com.stonewu.agenteam.mapper.permission.PermissionMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.permission.entity.ResourceCapability;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

/**
 * 上传资料沿用当前业务权限，已有文件编号不允许绕过资源授权。
 */
@Service
public class FilePurposeAuthorization {
    private final EnterpriseAuthorizationService enterprise;
    private final ResourceAuthorizationService resources;
    private final PermissionMapper permissions;

    public FilePurposeAuthorization(EnterpriseAuthorizationService enterprise, ResourceAuthorizationService resources,
                                    PermissionMapper permissions) {
        this.enterprise = enterprise;
        this.resources = resources;
        this.permissions = permissions;
    }

    public void upload(AuthContext actor, String purpose, String resourceId, boolean mutation) {
        String operation = switch (purpose) {
            case "skill_import" -> "skill.import";
            case "knowledge" -> "knowledge.edit";
            case "data_import" -> "data.edit";
            case "attachment" -> permissions.operationScope(actor.userId(), actor.enterpriseId(), "agent.run")
                .isPresent() ? "agent.run" : "agent.preview";
            default -> throw FileAccessService.unavailable();
        };
        if (mutation) {
            enterprise.lockAndRequire(actor, operation);
        } else {
            enterprise.require(actor, operation);
        }
        if (resourceId != null) {
            resources.require(actor, resourceId, operation, ResourceCapability.EDIT);
        }
    }
}
