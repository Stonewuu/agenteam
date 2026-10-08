package com.stonewu.agenteam.model.enterprise.entity;

import com.stonewu.agenteam.model.user.entity.UserEntity;

/**
 * 事务提交后只为本次真正新建的账户建立会话，已用凭据不能重新登录。
 */
public record InvitationAcceptance(UserEntity user, String enterpriseId, boolean createdAccount) {
}
