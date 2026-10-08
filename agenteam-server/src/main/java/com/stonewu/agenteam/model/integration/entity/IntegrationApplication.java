package com.stonewu.agenteam.model.integration.entity;

/**
 * 一个企业配置的外部应用；密钥和访问凭证不属于公开配置。
 */
public record IntegrationApplication(String id, String enterpriseId, String providerCode,
                                     String externalTenantId, String externalAppId, long credentialRevision) {
}
