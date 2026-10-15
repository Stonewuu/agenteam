package com.stonewu.agenteam.model.chat.response;

import java.util.Map;

/** 管理员看到真实检查结果，不返回应用秘密或连接票据。 */
public record ChatBotView(String id, String connectionId, String providerCode, String platformBotId,
                           String agentId, String agentVersionId, String versionPolicy, String roleName,
                           String roleDescription, String transport, String status, boolean chatEnabled,
                           long revision, Map<String, Object> capabilities, String lastConnectedAt, String lastMessageAt,
                           String callbackPath, boolean callbackConfigured, String connectionError, String agentName) {
}
