package com.stonewu.agenteam.model.auth.response;

/** 顶部身份提示和恢复入口所需信息，不暴露原会话凭据。 */
public record ImpersonationView(String id, String userId, String displayName, String username,
                                String administratorName, String enterpriseId, String returnPath) {
}
