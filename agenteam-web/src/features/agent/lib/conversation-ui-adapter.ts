import type {AppendMessage, ThreadMessageLike} from "@assistant-ui/react";
import type {DisplayMessage} from "../types/conversation-display";

export function toAssistantMessage(message: DisplayMessage): ThreadMessageLike {
  return {
    id: message.id, role: message.role === "user" ? "user" : "assistant", content: message.content,
    ...(message.role !== "user" ? {
      status: message.status === "pending" || message.status === "streaming"
        ? {type: "running" as const} : message.status === "failed" ? {
            type: "incomplete" as const,
            reason: "error" as const
          }
          : message.status === "cancelled" ? {
            type: "incomplete" as const,
            reason: "cancelled" as const
          } : {type: "complete" as const, reason: "stop" as const}
    } : {}),
    metadata: {custom: {conversationMessage: message}}
  };
}

export function readConversationMessage(metadata: { custom?: Record<string, unknown> }): DisplayMessage | null {
  const value = metadata.custom?.conversationMessage;
  if (!value || typeof value !== "object") {
    return null;
  }
  const message = value as DisplayMessage;
  return typeof message.id === "string" && typeof message.content === "string" && Array.isArray(message.blocks) ? message : null;
}

export function appendMessageText(message: AppendMessage) {
  if (typeof message.content === "string") {
    return message.content;
  }
  const parts = message.content as readonly { type: string; text?: string }[];
  return parts.reduce((text, part) => part.type === "text" ? text + (part.text ?? "") : text, "");
}
