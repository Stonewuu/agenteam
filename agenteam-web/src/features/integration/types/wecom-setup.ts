export type WecomSetupJob = {
  id: string;
  name: string;
  platformName: string | null;
  stage: "queued" | "awaiting_scan" | "verifying" | "binding" | "completed" | "failed" | "cancelled" | "expired";
  message: string | null;
  enterpriseConnectionId: string;
  connectionId: string | null;
  botId: string | null;
  canOpen: boolean;
  canRetry: boolean;
  canCancel: boolean;
  expiresAt: string | null;
  revision: number;
  updatedAt: string;
};

export type WecomSetupOverview = {
  enterprises: { id: string; name: string; externalTenantId: string }[];
  jobs: WecomSetupJob[];
};

export const finishedWecomStages = new Set(["completed", "cancelled", "expired"]);
