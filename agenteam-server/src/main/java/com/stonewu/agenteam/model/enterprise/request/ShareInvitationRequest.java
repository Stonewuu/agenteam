package com.stonewu.agenteam.model.enterprise.request;

import java.util.List;

/**
 * 分享邀请只携带角色与团队，目标企业由已验证的路径决定。
 */
public record ShareInvitationRequest(String displayName, List<String> roleIds, List<String> teamIds, String note) {
}
