package com.stonewu.agenteam.model.modelprofile.entity;

import java.time.Instant;

/**
 * 模型与当前提供方配置一起读取；密钥单独从提供方获取。
 */
public record ModelProfileRecord(String id, String enterpriseId, String providerId, String name, String modelName,
                                 ModelCapabilities capabilities, boolean enabled, long revision,
                                 String providerName, String provider, String baseUrl, boolean providerEnabled,
                                 Instant createdAt, Instant updatedAt) {
}
