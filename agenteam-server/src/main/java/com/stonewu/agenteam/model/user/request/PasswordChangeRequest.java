package com.stonewu.agenteam.model.user.request;

/**
 * 密码修改需要再次核验当前密码；记录文本隐藏两次输入。
 */
public record PasswordChangeRequest(String currentPassword, String newPassword) {
    @Override
    public String toString() {
        return "PasswordChangeRequest[密码已隐藏]";
    }
}
