package com.stonewu.agenteam.model.auth.entity;

import java.io.Serializable;

/** 原管理员认证信息仍在会话中，此记录只保存一次有效用户切换。 */
public record ImpersonationState(String id, String userId, long sessionVersion, String displayName,
                                 String username, String enterpriseId, String returnPath, long startedAt)
    implements Serializable {
}
