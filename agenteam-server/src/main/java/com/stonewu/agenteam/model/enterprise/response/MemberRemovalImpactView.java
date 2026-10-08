package com.stonewu.agenteam.model.enterprise.response;

public record MemberRemovalImpactView(String impactToken, String expiresAt, String memberRevision, long resourceCount,
                                      long ownedTeamCount, long openTodoCount, long activeRunCount,
                                      long enabledScheduleCount,
                                      boolean lastAdministrator, boolean canTransferOwnership) {
}
