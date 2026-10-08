package com.stonewu.agenteam.model.modelprofile.entity;

import java.time.Instant;

/**
 * 模型提供方；按当前开发要求直接保存密钥，不能作为接口响应。
 */
public record ModelProviderRecord(String id, String enterpriseId, String name, String protocol, String baseUrl,
                                  String apiKey, boolean enabled, long revision, Instant createdAt, Instant updatedAt) {
    @Override
    public String toString() {
        return "ModelProviderRecord[id=" + id + ", name=" + name + "]";
    }
}
