package com.stonewu.agenteam.model.enterprise.request;

/**
 * 接受邀请的凭据和新账号密码不得出现在日志中。
 */
public record InvitationAcceptRequest(String token, String username, String displayName, String password) {
    @Override
    public String toString() {
        return "InvitationAcceptRequest[邀请凭据及账号信息已隐藏]";
    }
}
