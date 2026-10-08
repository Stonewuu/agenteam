package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelprofile.response.RemoteModelView;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 读取远程模型前先检查模型管理权限和提供方所属企业。
 */
@Service
public class RemoteModelCatalogService {
    private final ModelManagementService management;
    private final ModelProviderMapper providers;
    private final RemoteModelClient client;

    public RemoteModelCatalogService(ModelManagementService management, ModelProviderMapper providers,
                                     RemoteModelClient client) {
        this.management = management;
        this.providers = providers;
        this.client = client;
    }

    public List<RemoteModelView> list(AuthContext actor, String providerId) {
        management.authorize(actor, false);
        var provider = providers.find(actor.enterpriseId(), providerId)
            .orElseThrow(ResourceAuthorizationService::unavailable);
        return client.list(provider);
    }
}
