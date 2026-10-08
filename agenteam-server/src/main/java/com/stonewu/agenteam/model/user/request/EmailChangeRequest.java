package com.stonewu.agenteam.model.user.request;

/**
 * 申请新邮箱验证，确认完成前保留旧邮箱。
 */
public record EmailChangeRequest(String newEmail, String currentPassword) {
    @Override
    public String toString() {
        return "EmailChangeRequest[账号验证信息已隐藏]";
    }
}
