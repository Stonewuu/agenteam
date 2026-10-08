package com.stonewu.agenteam.model.enterprise.response;

/**
 * 持有有效邀请链接的人可以确认邀请来源，邮箱只显示部分字符。
 */
public record InvitationPreview(String enterpriseName, String inviterName, String maskedEmail, String expiresAt) {
}
