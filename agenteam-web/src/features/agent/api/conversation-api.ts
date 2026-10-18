import {ApiError, apiRequest, notifyAccessFailure, parseApiResponse} from "@/lib/http/api-client";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ApiPage} from "@/lib/http/use-api-query";
import type {
  Conversation,
  ConversationNavigationItem,
  ConversationFrame,
  ConversationSnapshot,
  MessageHistory,
  MessageInput,
  Run
} from "../types/execution";
import {navigationConversation} from "../lib/conversation-navigation";
import {parseConversationFrame, SnapshotRequired, validateSnapshot} from "../state/conversation-protocol";

export function conversationPath(enterprise: string, conversation?: string) {
  return organizationPath(enterprise, `/conversations${conversation ? `/${encodeURIComponent(conversation)}` : ""}`);
}

export function runPath(enterprise: string, run: string) {
  return organizationPath(enterprise, `/runs/${encodeURIComponent(run)}`);
}

export function textInput(text: string): MessageInput {
  return {text, attachmentIds: [], skillVersionIds: [], knowledgeReferences: [], links: []};
}

export type ConversationFilter = {
  scope?: "all" | "standalone" | "collaboration";
  query: string;
  status: Conversation["status"];
  favorite: boolean;
  agentId: string;
};

export async function listConversations(enterprise: string, filter: ConversationFilter, cursor?: string | null, signal?: AbortSignal) {
  const params = new URLSearchParams({status: filter.status, limit: "30"});
  params.set("scope", filter.scope ?? "all");
  if (filter.query) {
    params.set("query", filter.query);
  }
  if (filter.favorite) {
    params.set("favorite", "true");
  }
  if (filter.agentId) {
    params.set("agentId", filter.agentId);
  }
  if (cursor) {
    params.set("cursor", cursor);
  }
  const result = await apiRequest<ApiPage<ConversationNavigationItem>>(`${conversationPath(enterprise)}/navigation?${params}`, {signal});
  return {...result, items: result.items.map(navigationConversation)};
}

export async function loadSnapshot(enterprise: string, conversation: string, signal?: AbortSignal) {
  const snapshot = await apiRequest<ConversationSnapshot>(conversationPath(enterprise, conversation), {signal});
  validateSnapshot(snapshot);
  return snapshot;
}

export async function loadConversationMetadata(enterprise: string, ids: readonly string[], signal?: AbortSignal) {
  const params = new URLSearchParams();
  ids.forEach(id => params.append("ids", id));
  const result = await apiRequest<{conversation: Conversation; titlePending: boolean}[]>(
    `${conversationPath(enterprise)}/metadata?${params}`, {signal});
  if (!Array.isArray(result) || result.some(item => !item.conversation || !ids.includes(item.conversation.id)
    || !/^[1-9][0-9]*$/.test(item.conversation.revision) || typeof item.conversation.title !== "string"
    || typeof item.titlePending !== "boolean")) {
    throw new Error("对话基本信息格式不正确。");
  }
  return result;
}

export function olderMessages(enterprise: string, conversation: string, before: string, signal?: AbortSignal) {
  return apiRequest<MessageHistory>(`${conversationPath(enterprise, conversation)}/messages?${new URLSearchParams({
    beforeMessageId: before,
    limit: "30"
  })}`, {signal});
}

export function loadRun(enterprise: string, run: string, signal?: AbortSignal) {
  return apiRequest<Run>(runPath(enterprise, run), {signal});
}

/** 从完整快照对应的位置接续输出，断开连接不会停止后台执行。 */
export async function readConversationEvents({enterprise, conversation, after, generation, signal, onFrame}: {
  enterprise: string;
  conversation: string;
  after: string;
  generation?: string;
  signal: AbortSignal;
  onFrame: (frame: ConversationFrame) => void;
}) {
  const request = new AbortController();
  const abort = () => request.abort();
  if (signal.aborted) {
    abort();
  } else {
    signal.addEventListener("abort", abort, {once: true});
  }
  let idle = setTimeout(abort, 45000);
  const received = () => {
    clearTimeout(idle);
    idle = setTimeout(abort, 45000);
  };
  try {
    const params = new URLSearchParams({after});
    if (generation) {
      params.set("generation", generation);
    }
    const path = `${conversationPath(enterprise, conversation)}/events?${params}`;
    const response = await fetch(path, {
      headers: {Accept: "text/event-stream"},
      cache: "no-store",
      credentials: "same-origin",
      signal: request.signal
    });
    if (!response.ok) {
      try {
        await parseApiResponse(response);
      } catch (error) {
        if (error instanceof ApiError) {
          notifyAccessFailure(path, error);
        }
        throw error;
      }
    }
    if (!response.body || !response.headers.get("content-type")?.includes("text/event-stream")) {
      throw new SnapshotRequired();
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    try {
      for (; ;) {
        const {done, value} = await reader.read();
        received();
        buffer += decoder.decode(value ?? new Uint8Array(), {stream: !done});
        const frames = buffer.split(/\r?\n\r?\n/);
        buffer = frames.pop() ?? "";
        if (buffer.length > 4 * 1024 * 1024) {
          throw new SnapshotRequired();
        }
        for (const frame of frames) {
          if (frame.length > 4 * 1024 * 1024) {
            throw new SnapshotRequired();
          }
          const parsed = parseConversationFrame(frame);
          if (parsed) {
            onFrame(parsed);
          }
        }
        if (done) {
          if (buffer.trim()) {
            throw new SnapshotRequired();
          }
          break;
        }
      }
    } finally {
      await reader.cancel().catch(() => undefined);
      reader.releaseLock();
    }
  } finally {
    clearTimeout(idle);
    signal.removeEventListener("abort", abort);
  }
}
