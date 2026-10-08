package com.stonewu.agenteam.model.enterprise.entity;

import java.time.Instant;
import java.util.List;

/**
 * 邀请业务记录，禁止将内部实体直接作为接口响应。
 */
public record InvitationEntity(String id, String enterpriseId, String email, String displayName, List<String> teamIds,
                               List<String> roleIds,
                               String tokenHash, String status, String createdBy, String acceptedUserId,
                               Instant expiresAt, long revision) {
}
