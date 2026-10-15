export type ReplyMode = "chat_full" | "summary_link";

export type ReplyPreference = {
  conversationId: string; platform: string; kind: "group" | "direct"; name: string;
  outputVisibility: ReplyMode; revision: number; fullAllowed: boolean; botNames?: string[];
};

export type TaskReplyMode = {
  taskId: string; revision: number; outputVisibility: ReplyMode;
  conversation: ReplyPreference; canSendResult: boolean;
};

export function replyPreferencesPath(enterprise: string) {
  return `/api/v1/enterprises/${encodeURIComponent(enterprise)}/chat/reply-preferences`;
}
