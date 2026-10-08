package com.stonewu.agenteam.model.enterprise.response;

import java.util.List;

/**
 * 邀请管理视图不包含凭据、投递正文或内部错误。
 */
public record InvitationView(String id, String revision, String createdAt, String updatedAt, String email,
                             String displayName,
                             List<String> teamIds, List<String> roleIds, String status, String deliveryStatus,
                             String expiresAt, ActorView createdBy) {
}
