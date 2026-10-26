export type FeishuSetupJob = {
  id: string;
  kind: "management" | "enterprise" | "bot";
  name: string;
  stage: string;
  message: string;
  managerConnectionId: string | null;
  connectionId: string | null;
  botId: string | null;
  canOpen: boolean;
  canRetry: boolean;
  canCancel: boolean;
  expiresAt: string | null;
  revision: number;
  updatedAt: string;
};

export type FeishuManager = {
  connectionId: string;
  name: string;
  tenantId: string;
  authorized: boolean;
  purpose: "enterprise" | "app_management";
  ready: boolean;
  loginEnabled: boolean;
  messagingEnabled: boolean;
  bindingEnabled: boolean;
};
export type FeishuSetupOverview = { managers: FeishuManager[]; jobs: FeishuSetupJob[] };

export const finishedFeishuStages = new Set(["completed", "cancelled", "expired", "registration_unknown"]);
