package com.stonewu.agenteam.model.integration.response;

import java.util.Map;

/**
 * 仅返回接入的必要配置与实际校验结果，不返回任何密文或明文凭据。
 */
public record IntegrationView(String id, String enterpriseId, String providerCode, String providerName,
                              String name, String externalTenantId, String externalAppId, String status,
                              boolean bindingEnabled, boolean loginEnabled, boolean messagingEnabled,
                              boolean secretConfigured, String callbackUrl, String loginUrl,
                              String lastCheckStatus, String lastCheckedAt, String lastCheckError,
                              String revision, String createdAt, String updatedAt, Map<String, Object> configuration) {
}
