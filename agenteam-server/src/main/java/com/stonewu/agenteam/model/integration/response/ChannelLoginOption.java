package com.stonewu.agenteam.model.integration.response;

/** 登录页只公开入口标识及显示名称，不返回企业、应用凭据或内部配置。 */
public record ChannelLoginOption(String key, String name, String providerCode, String providerName) {
}
