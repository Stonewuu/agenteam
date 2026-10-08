export type MemberRemovalImpact = {
  impactToken: string;
  expiresAt: string;
  memberRevision: string;
  resourceCount: number;
  ownedTeamCount: number;
  openTodoCount: number;
  activeRunCount: number;
  enabledScheduleCount: number;
  lastAdministrator: boolean;
  canTransferOwnership: boolean;
};
