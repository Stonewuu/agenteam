package com.stonewu.agenteam.model.mail.entity;

import java.time.Instant;

/**
 * 邮件发送前从当前邀请读取的业务依据。
 */
public record InvitationMailTarget(String id, String enterpriseId, String creatorId, String email, String tokenHash,
                                   String status,
                                   Instant expiresAt, String enterpriseStatus, String memberStatus, String userStatus,
                                   String roleIdsJson, String teamIdsJson) {
}
