package com.stonewu.agenteam.model.auth.entity;

import java.time.Instant;

/**
 * 一次性验证记录；校验表不保存原始凭据。
 */
public record AuthToken(String id, String userId, String purpose, String tokenHash, String targetEmail,
                        Instant expiresAt, Instant consumedAt, Instant createdAt) {
}
