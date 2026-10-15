package com.stonewu.agenteam.model.chat.request;

/** 平台身份来自接入校验，客户端只选择已有应用和岗位。 */
public record ChatBotWriteRequest(String requestId, String connectionId, String agentId, String versionPolicy,
                                  String pinnedVersionId, String roleName, String roleDescription, String transport,
                                  Long expectedRevision, String status, Boolean chatEnabled) {
}
