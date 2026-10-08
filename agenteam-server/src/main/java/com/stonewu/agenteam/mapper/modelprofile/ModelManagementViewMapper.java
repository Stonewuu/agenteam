package com.stonewu.agenteam.mapper.modelprofile;


import com.stonewu.agenteam.model.modelprofile.entity.ModelProfileRecord;
import com.stonewu.agenteam.model.modelprofile.entity.ModelProviderRecord;
import com.stonewu.agenteam.model.modelprofile.response.ManagedModelProfileView;
import com.stonewu.agenteam.model.modelprofile.response.ModelProviderView;
import org.springframework.stereotype.Component;

/**
 * 管理响应只包含页面所需配置，不包含模型密钥。
 */
@Component
public class ModelManagementViewMapper {
    private final ModelProviderMapper providers;
    private final ModelProfileMapper profiles;
    private final ModelProfileSqlMapper modelProfileSqlMapper;

    public ModelManagementViewMapper(ModelProviderMapper providers, ModelProfileMapper profiles,
                                     ModelProfileSqlMapper modelProfileSqlMapper) {
        this.modelProfileSqlMapper = modelProfileSqlMapper;
        this.providers = providers;
        this.profiles = profiles;
    }

    public ModelProviderView provider(ModelProviderRecord value) {
        return new ModelProviderView(value.id(), Long.toString(value.revision()), value.name(), value.protocol(),
            value.baseUrl(),
            !value.apiKey().isEmpty(), value.enabled(),
            modelProfileSqlMapper.modelCount(value.enterpriseId(), value.id()),
            profiles.providerInUse(value.enterpriseId(), value.id()),
            value.createdAt().toString(), value.updatedAt().toString());
    }

    public ManagedModelProfileView model(ModelProfileRecord value) {
        return new ManagedModelProfileView(value.id(), Long.toString(value.revision()), value.providerId(),
            value.providerName(), value.providerEnabled(),
            value.name(), value.modelName(), value.capabilities(), value.enabled(),
            profiles.inUse(value.enterpriseId(), value.id()),
            value.createdAt().toString(), value.updatedAt().toString());
    }
}
