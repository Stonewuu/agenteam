package com.stonewu.agenteam.model.integration.request;

/** 明确确认页面展示的授权记录，不能被另一个标签页的回调替换。 */
public record ChannelBindingConfirmRequest(String authorizationId, Boolean receiveEnabled, Boolean externalLoginEnabled) {
    public ChannelBindingUpdateRequest choices() {
        return new ChannelBindingUpdateRequest(receiveEnabled, externalLoginEnabled);
    }
}
