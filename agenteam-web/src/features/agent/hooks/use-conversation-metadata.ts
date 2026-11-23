"use client";

import {useEffect, useEffectEvent} from "react";
import {ApiError} from "@/lib/http/api-client";
import {loadConversationMetadata} from "../api/conversation-api";
import type {Conversation} from "../types/execution";

const SETTLED_TTL_MS = 60000;
const settledMetadata = new Map<string, number>();

function metadataKey(enterprise: string, id: string, revision: string) {
  return `${enterprise}:${id}:${revision}`;
}

function isSettled(enterprise: string, id: string, revision: string, now: number) {
  if (!revision) {
    return false;
  }
  const key = metadataKey(enterprise, id, revision);
  const until = settledMetadata.get(key);
  if (until === undefined) {
    return false;
  }
  if (until <= now) {
    settledMetadata.delete(key);
    return false;
  }
  return true;
}

function markSettled(enterprise: string, id: string, revision: string, now: number) {
  if (!revision) {
    return;
  }
  if (settledMetadata.size >= 500) {
    const oldest = settledMetadata.keys().next().value;
    if (oldest) {
      settledMetadata.delete(oldest);
    }
  }
  settledMetadata.set(metadataKey(enterprise, id, revision), now + SETTLED_TTL_MS);
}

/** 后台独立生成，页面只读取结果；已确认无待生成标题的同一版本短期内不重复查询。 */
export function useConversationMetadata(enterprise: string, items: readonly { id: string; revision: string }[], enabled: boolean,
                                        onUpdate: (values: Conversation[]) => void) {
  const unique = new Map<string, string>();
  for (const item of items) {
    const previous = unique.get(item.id);
    if (!previous || item.revision) {
      unique.set(item.id, item.revision);
    }
  }
  const binding = JSON.stringify([...unique.entries()].sort(([left], [right]) => left.localeCompare(right)));
  const update = useEffectEvent(onUpdate);
  useEffect(() => {
    const entries = JSON.parse(binding) as [string, string][];
    if (!enabled || !entries.length) {
      return;
    }
    const selectPending = () => {
      const now = Date.now();
      return entries.filter(([id, revision]) => !isSettled(enterprise, id, revision, now)).map(([id]) => id);
    };
    let pending = selectPending();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let request: AbortController | undefined;
    let stopped = false;
    let failures = 0;

    async function read() {
      if (stopped || document.hidden || !pending.length) {
        return;
      }
      const current = new AbortController();
      request = current;
      try {
        const next: string[] = [];
        const values: Conversation[] = [];
        const now = Date.now();
        for (let start = 0; start < pending.length; start += 50) {
          const batch = await loadConversationMetadata(enterprise, pending.slice(start, start + 50), current.signal);
          for (const item of batch) {
            values.push(item.conversation);
            if (item.titlePending) {
              next.push(item.conversation.id);
            } else {
              markSettled(enterprise, item.conversation.id, item.conversation.revision, now);
            }
          }
        }
        if (stopped || current.signal.aborted) {
          return;
        }
        update(values);
        pending = next;
        failures = 0;
      } catch (failure) {
        if (stopped || current.signal.aborted) {
          return;
        }
        if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
          pending = [];
          return;
        }
        failures++;
        console.warn("Conversation title is not updated yet; retrying later.", failure);
      }
      if (!stopped && !current.signal.aborted && pending.length && !document.hidden) {
        timer = setTimeout(() => void read(), Math.min(1000 * 2 ** Math.min(failures, 4), 15000));
      }
    }

    const visible = () => {
      clearTimeout(timer);
      request?.abort();
      if (!document.hidden) {
        pending = selectPending();
        if (pending.length) {
          timer = setTimeout(() => void read(), 0);
        }
      }
    };
    if (pending.length) {
      timer = setTimeout(() => void read(), 0);
    }
    document.addEventListener("visibilitychange", visible);
    return () => {
      stopped = true;
      clearTimeout(timer);
      request?.abort();
      document.removeEventListener("visibilitychange", visible);
    };
  }, [enterprise, binding, enabled]);
}
