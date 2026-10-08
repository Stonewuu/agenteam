package com.stonewu.agenteam.mapper.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.model.integration.entity.EnterpriseIntegrationRow;
import com.stonewu.agenteam.service.integration.IntegrationProviderRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 只保存和返回平台适配器明确允许的非敏感参数。 */
@Component
public class IntegrationConfigurationMapper {
    private final IntegrationProviderRegistry providers;
    private final ObjectMapper json;

    public IntegrationConfigurationMapper(IntegrationProviderRegistry providers, ObjectMapper json) {
        this.providers = providers;
        this.json = json;
    }

    public String encode(String provider, Map<String, Object> configuration) {
        try {
            return json.writeValueAsString(providers.require(provider).normalizeConfiguration(configuration));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("接入参数无法保存", failure);
        }
    }

    public Map<String, Object> read(EnterpriseIntegrationRow row) {
        try {
            Map<String, Object> configuration = json.readValue(row.getConfigJson(), new TypeReference<>() {
            });
            return providers.require(row.getProviderCode()).normalizeConfiguration(configuration);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("接入参数无法读取", failure);
        }
    }
}
