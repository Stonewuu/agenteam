package com.stonewu.agenteam.model.auth.response;

/** 目标账号不可用时仍能读取原管理员的返回能力。 */
public record ImpersonationStatus(String identityRevision, ImpersonationView impersonation) {
}
