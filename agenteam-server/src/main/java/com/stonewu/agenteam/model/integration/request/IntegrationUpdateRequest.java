package com.stonewu.agenteam.model.integration.request;

import java.util.Map;

/**
 * 外部企业与应用身份不可就地更换，只接受名称、能力开关和渠道允许的公开配置。
 */
public record IntegrationUpdateRequest(String name, Boolean bindingEnabled, Boolean loginEnabled,
                                       Boolean messagingEnabled, Map<String, Object> configuration) {
}
