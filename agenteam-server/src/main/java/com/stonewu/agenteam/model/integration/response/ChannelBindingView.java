package com.stonewu.agenteam.model.integration.response;

/** 本人可见的绑定情况，不包含应用密钥或用户授权凭证。 */
public record ChannelBindingView(String id, String connectionId, String connectionName, String providerCode,
                                  String providerName, String status, String displayName, boolean receiveEnabled,
                                  boolean externalLoginEnabled, String revision, boolean bindingAvailable,
                                  boolean loginAvailable, boolean messagingAvailable) {
}
