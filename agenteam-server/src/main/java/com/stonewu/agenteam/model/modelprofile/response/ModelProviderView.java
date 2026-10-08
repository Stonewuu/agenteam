package com.stonewu.agenteam.model.modelprofile.response;

/**
 * 管理页面只读取密钥是否已配置，不返回保存的内容。
 */
public record ModelProviderView(String id, String revision, String name, String protocol, String baseUrl,
                                boolean keyConfigured, boolean enabled, int modelCount, boolean inUse,
                                String createdAt, String updatedAt) {
}
