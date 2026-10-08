package com.stonewu.agenteam.model.integration.response;

/** 只在原浏览器展示待确认的本地账号和本次取得的外部身份。 */
public record ChannelAuthorizationReview(String authorizationId, String enterpriseId, String connectionName, String providerName,
                                          String username, String localDisplayName, String externalDisplayName,
                                          String externalSubjectId, String expiresAt, boolean loginAvailable) {
}
