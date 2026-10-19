package com.stonewu.agenteam.service.modelprofile;

import com.stonewu.agenteam.mapper.modelprofile.ModelManagementViewMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProfileSqlMapper;
import com.stonewu.agenteam.mapper.modelprofile.ModelProviderMapper;
import com.stonewu.agenteam.model.auth.entity.AuthContext;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelprofile.request.ModelProfileWriteRequest;
import com.stonewu.agenteam.model.modelprofile.request.ModelProviderWriteRequest;
import com.stonewu.agenteam.model.modelprofile.response.ManagedModelProfileView;
import com.stonewu.agenteam.model.modelprofile.response.ModelProviderView;
import com.stonewu.agenteam.service.http.ApiException;
import com.stonewu.agenteam.service.permission.EnterpriseAuthorizationService;
import com.stonewu.agenteam.service.permission.ResourceAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理修改与授权、审计一起提交；删除配置时保留历史记录所需的关联。
 */
@Service
public class ModelManagementService {
    private final ModelProviderMapper providers;
    private final ModelProfileMapper profiles;
    private final ModelManagementViewMapper views;
    private final ModelConfigurationValidation validation;
    private final EnterpriseAuthorizationService authorization;
    private final Clock clock;

    private final ModelProfileSqlMapper modelProfileSqlMapper;

    public ModelManagementService(ModelProviderMapper providers, ModelProfileMapper profiles,
                                  ModelManagementViewMapper views,
                                  ModelConfigurationValidation validation, EnterpriseAuthorizationService authorization,
                                  Clock clock, ModelProfileSqlMapper modelProfileSqlMapper) {
        this.modelProfileSqlMapper = modelProfileSqlMapper;
        this.providers = providers;
        this.profiles = profiles;
        this.views = views;
        this.validation = validation;
        this.authorization = authorization;
        this.clock = clock;
    }

    public void authorize(AuthContext actor, boolean mutation) {
        String permission = !mutation && !actor.permissions().contains("model.manage") ? "model.view" : "model.manage";
        authorization.requireEnterpriseScope(
            mutation ? authorization.lockAndRequire(actor, permission) : authorization.require(actor, permission));
    }

    public List<ModelProviderView> providers(AuthContext actor) {
        authorize(actor, false);
        return providers.list(actor.enterpriseId()).stream().map(views::provider).toList();
    }

    public List<ManagedModelProfileView> models(AuthContext actor) {
        authorize(actor, false);
        return profiles.list(actor.enterpriseId()).stream().map(views::model).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelProviderView createProvider(AuthContext actor, ModelProviderWriteRequest input) {
        authorize(actor, true);
        var value = validation.provider(input);
        String id = UUID.randomUUID().toString();
        try {
            providers.insert(actor.enterpriseId(), id, value, clock.instant());
        } catch (DuplicateKeyException duplicate) {
            throw duplicateName();
        }
        authorization.changed(actor, "model_provider.create", "model_provider", id, "新增模型提供方",
            Map.of("name", value.name()));
        return views.provider(provider(actor, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ModelProviderView updateProvider(AuthContext actor, String id, long revision,
                                            ModelProviderWriteRequest input) {
        authorize(actor, true);
        var current = provider(actor, id);
        checkRevision(current.revision(), revision);
        var value = validation.provider(input);
        try {
            checkChanged(providers.update(actor.enterpriseId(), id, value, clock.instant()), revision);
        } catch (DuplicateKeyException duplicate) {
            throw duplicateName();
        }
        authorization.changed(actor, "model_provider.update", "model_provider", id, "修改模型提供方",
            Map.of("enabled", value.enabled(), "keyChanged", value.apiKey() != null));
        return views.provider(provider(actor, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteProvider(AuthContext actor, String id, long revision) {
        authorize(actor, true);
        checkRevision(provider(actor, id).revision(), revision);
        if (modelProfileSqlMapper.modelCount(actor.enterpriseId(), id) > 0) {
            throw inUse("该提供方下仍有模型，请先删除模型后再删除提供方。");
        }
        checkChanged(providers.delete(actor.enterpriseId(), id, revision, clock.instant()), revision);
        authorization.changed(actor, "model_provider.delete", "model_provider", id, "删除模型提供方", Map.of());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ManagedModelProfileView createModel(AuthContext actor, ModelProfileWriteRequest input) {
        authorize(actor, true);
        var value = validation.model(input);
        new ModelProtocolValidation().validate(provider(actor, value.providerId()).protocol(), value.capabilities());
        String id = UUID.randomUUID().toString();
        try {
            profiles.insert(actor.enterpriseId(), id, value, clock.instant());
        } catch (DuplicateKeyException duplicate) {
            throw duplicateName();
        }
        authorization.changed(actor, "model_profile.create", "model_profile", id, "新增模型",
            Map.of("name", value.name(), "providerId", value.providerId()));
        return views.model(model(actor, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ManagedModelProfileView updateModel(AuthContext actor, String id, long revision,
                                               ModelProfileWriteRequest input) {
        authorize(actor, true);
        var current = model(actor, id);
        checkRevision(current.revision(), revision);
        var value = validation.model(input);
        new ModelProtocolValidation().validate(provider(actor, value.providerId()).protocol(), value.capabilities());
        try {
            checkChanged(profiles.update(actor.enterpriseId(), id, value, clock.instant()), revision);
        } catch (DuplicateKeyException duplicate) {
            throw duplicateName();
        }
        authorization.changed(actor, "model_profile.update", "model_profile", id, "修改模型配置",
            Map.of("name", value.name(), "enabled", value.enabled()));
        return views.model(model(actor, id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteModel(AuthContext actor, String id, long revision) {
        authorize(actor, true);
        checkRevision(model(actor, id).revision(), revision);
        checkChanged(profiles.delete(actor.enterpriseId(), id, revision, clock.instant()), revision);
        authorization.changed(actor, "model_profile.delete", "model_profile", id, "删除模型", Map.of());
    }

    private ModelProviderRecord provider(AuthContext actor, String id) {
        return providers.find(actor.enterpriseId(), id).orElseThrow(ResourceAuthorizationService::unavailable);
    }

    private ModelProfileRecord model(AuthContext actor, String id) {
        return profiles.find(actor.enterpriseId(), id).orElseThrow(ResourceAuthorizationService::unavailable);
    }

    private void checkRevision(long current, long requested) {
        if (current != requested) {
            throw ApiException.versionConflict(current);
        }
    }

    private ApiException duplicateName() {
        return ApiException.invalidField("name", "当前企业已存在同名配置，请修改名称。");
    }

    private void checkChanged(int changed, long revision) {
        if (changed != 1) {
            throw ApiException.versionConflict(revision);
        }
    }

    private ApiException inUse(String message) {
        return new ApiException(HttpStatus.CONFLICT, "MODEL_CONFIGURATION_IN_USE", message);
    }
}
