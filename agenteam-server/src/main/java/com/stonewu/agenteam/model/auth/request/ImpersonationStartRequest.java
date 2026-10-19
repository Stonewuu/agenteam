package com.stonewu.agenteam.model.auth.request;

/** 从明确的成员列表切换，不接受客户端传入管理员身份。 */
public record ImpersonationStartRequest(String userId, String enterpriseId, String returnPath) {
}
