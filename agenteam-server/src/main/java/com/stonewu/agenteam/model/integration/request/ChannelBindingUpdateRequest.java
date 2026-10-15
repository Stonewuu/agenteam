package com.stonewu.agenteam.model.integration.request;

/** 用户分别决定通知、登录与聊天，旧客户端不修改已有聊天选择。 */
public record ChannelBindingUpdateRequest(Boolean receiveEnabled, Boolean externalLoginEnabled, Boolean chatEnabled) {
    public ChannelBindingUpdateRequest(Boolean receiveEnabled, Boolean externalLoginEnabled) {
        this(receiveEnabled, externalLoginEnabled, null);
    }
}
