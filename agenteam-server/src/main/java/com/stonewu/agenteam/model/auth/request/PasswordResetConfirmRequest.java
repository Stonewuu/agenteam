package com.stonewu.agenteam.model.auth.request;

/**
 * 凭一次性邮件链接设置新密码。
 */
public record PasswordResetConfirmRequest(String token, String newPassword) {
    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[凭据与密码已隐藏]";
    }
}
