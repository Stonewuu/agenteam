package com.stonewu.agenteam.model.auth.entity;

/** 业务归属使用有效用户，真实管理员用于追溯发起者。 */
public record OperationActor(String authenticatedUserId, String effectiveUserId, String impersonationId) {
}
