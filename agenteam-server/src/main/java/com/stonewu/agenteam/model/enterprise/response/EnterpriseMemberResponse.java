package com.stonewu.agenteam.model.enterprise.response;

import java.util.List;

/**
 * 企业成员及其角色、团队。
 */
public record EnterpriseMemberResponse(
    String userId,
    String username,
    String displayName,
    String status,
    List<String> roleIds,
    List<String> roles,
    List<String> teams,
    long revision) {
}
