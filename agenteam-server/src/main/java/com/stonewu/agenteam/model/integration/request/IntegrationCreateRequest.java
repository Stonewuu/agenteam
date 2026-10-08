package com.stonewu.agenteam.model.integration.request;

import java.util.Map;

/**
 * 新建企业接入；秘密只允许写入，不参与接口响应。
 */
public record IntegrationCreateRequest(String providerCode, String name, String externalTenantId,
                                       String externalAppId, String secret, Boolean bindingEnabled,
                                       Boolean loginEnabled, Boolean messagingEnabled, Map<String, Object> configuration) {
    @Override
    public String toString() {
        return "IntegrationCreateRequest[接入配置和密钥已隐藏]";
    }
}
