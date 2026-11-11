package com.stonewu.agenteam.model.enterprise.response;

import com.stonewu.agenteam.service.i18n.Messages;

/**
 * 创建结果供管理员复制；列表和审计中不返回邀请码。
 */
public record IssuedInvitationView(InvitationView invitation, String code, String registrationPath) {
    @Override
    public String toString() {
        return Messages.log("log.enterprise.issuedInvitationView.toString.1");
    }
}
