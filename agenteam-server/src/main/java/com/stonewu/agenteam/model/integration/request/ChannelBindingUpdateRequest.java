package com.stonewu.agenteam.model.integration.request;

/** 用户单独决定通知接收和外部登录。 */
public record ChannelBindingUpdateRequest(Boolean receiveEnabled, Boolean externalLoginEnabled) {
}
