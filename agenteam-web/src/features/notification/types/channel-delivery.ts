export type ChannelDelivery = {
  id: string;
  notificationId: string;
  recipientName: string;
  connectionId: string;
  connectionName: string;
  providerName: string;
  status: "pending" | "sending" | "retry_wait" | "accepted" | "failed" | "blocked" | "cancelled" | "unknown" | "expired";
  attemptCount: number;
  manualRetryCount: number;
  nextAttemptAt: string | null;
  expiresAt: string;
  acceptedAt: string | null;
  errorSummary: string | null;
  canRetry: boolean;
  duplicateConfirmationRequired: boolean;
  revision: string;
  createdAt: string;
};

export type ChannelDeliveryDetail = {
  delivery: ChannelDelivery;
  attempts: {number: number; outcome: string; summary: string | null; startedAt: string; finishedAt: string | null}[];
};

export const deliveryStatuses: Record<ChannelDelivery["status"], string> = {
  pending: "等待发送", sending: "正在发送", retry_wait: "等待再次尝试", accepted: "平台已接受",
  failed: "发送失败", blocked: "未能发送", cancelled: "已停止发送", unknown: "结果无法确认", expired: "已过期"
};

export function channelDeliveryBase(enterprise: string, system = false) {
  return `/api/v1/${system ? "system/" : ""}enterprises/${encodeURIComponent(enterprise)}`;
}

export function deliveryInProgress(value: ChannelDelivery) {
  return ["pending", "sending", "retry_wait"].includes(value.status);
}
