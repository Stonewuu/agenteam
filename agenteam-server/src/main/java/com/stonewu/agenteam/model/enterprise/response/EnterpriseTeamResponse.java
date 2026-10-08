package com.stonewu.agenteam.model.enterprise.response;

import java.util.List;

/**
 * 企业团队及其成员。
 */
public record EnterpriseTeamResponse(
    String teamId,
    String name,
    String description,
    String status,
    List<String> memberUserIds,
    String ownerUserId,
    long revision) {
}
