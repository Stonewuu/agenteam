export type ChatBot = {
  id: string; connectionId: string; providerCode: string; platformBotId: string; agentId: string;
  agentName?: string | null;
  agentVersionId: string | null; versionPolicy: "pinned" | "latest_on_new_task"; roleName: string; roleDescription: string;
  transport: "websocket" | "webhook"; status: "draft" | "enabled" | "disabled"; chatEnabled: boolean; revision: number;
  capabilities: Record<string, {status: string; checkedAt?: string}>;
  lastConnectedAt: string | null; lastMessageAt: string | null; callbackPath: string | null; callbackConfigured: boolean; connectionError: string | null;
};

export type ChatGroup = {
  id: string; platformCode: string; externalConversationId: string; name: string | null; kind: "group" | "direct";
  status: string; ingressMode: "single_bot" | "multi_bot"; ingressBotId: string | null; defaultBotId: string | null;
  triggerMode: "mention"; resultPolicy: "summary_link" | "full_allowed"; revision: number; limits: Record<string, number>;
  bots: {botId: string; roleName: string; membershipStatus: string; receiveEnabled: boolean; sendEnabled: boolean; collaborationEnabled: boolean; verifiedAt: string | null; crossBotIdentityVerified: boolean}[];
};

export type ChatOutbound = {
  id: string; conversationId: string; botId: string; kind: string; status: string; attemptCount: number;
  errorCode: string | null; platformMessageId: string | null; revision: number; createdAt: string; updatedAt: string;
};

export const chatPath = (enterprise: string, system = false) => `/api/v1/${system ? "system/" : ""}enterprises/${encodeURIComponent(enterprise)}/chat`;
export const deliveryStatus: Record<string, string> = {
  pending: "等待发送", leased: "准备发送", sending: "正在发送", retry_wait: "等待重试", sent: "平台已接受",
  failed: "发送失败", cancelled: "已取消发送", unknown: "发送结果未知",
};
