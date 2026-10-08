package com.stonewu.agenteam.model.enterprise.response;

/**
 * 创建结果供管理员复制；列表和审计中不返回邀请码。
 */
public record IssuedInvitationView(InvitationView invitation, String code, String registrationPath) {
    @Override
    public String toString() {
        return "IssuedInvitationView[邀请凭据已隐藏]";
    }
}
