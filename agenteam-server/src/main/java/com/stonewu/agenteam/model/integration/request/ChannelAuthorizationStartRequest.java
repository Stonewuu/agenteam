package com.stonewu.agenteam.model.integration.request;

/** 客户端类型只影响授权页面，不参与企业、用户或权限判断。 */
public record ChannelAuthorizationStartRequest(Boolean embeddedClient) {
}
