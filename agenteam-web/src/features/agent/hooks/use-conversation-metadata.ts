"use client";

import {useEffect, useEffectEvent} from "react";
import {ApiError} from "@/lib/http/api-client";
import {loadConversationMetadata} from "../api/conversation-api";
import type {Conversation} from "../types/execution";

/** 后台独立生成，页面只读取结果；单个定时器集中刷新可见对话。 */
export function useConversationMetadata(enterprise: string, ids: readonly string[], enabled: boolean,
                                        onUpdate: (values: Conversation[]) => void) {
  const binding = JSON.stringify([...new Set(ids)].sort());
  const update = useEffectEvent(onUpdate);
  useEffect(() => {
    const all = JSON.parse(binding) as string[];
    if (!enabled || !all.length) {
      return;
    }
    let pending = all;
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
        for (let start = 0; start < pending.length; start += 50) {
          const batch = await loadConversationMetadata(enterprise, pending.slice(start, start + 50), current.signal);
          for (const item of batch) {
            values.push(item.conversation);
            if (item.titlePending) {
              next.push(item.conversation.id);
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
        console.warn("对话标题暂未刷新，稍后继续读取。", failure);
      }
      if (!stopped && !current.signal.aborted && pending.length && !document.hidden) {
        timer = setTimeout(() => void read(), Math.min(1000 * 2 ** Math.min(failures, 4), 15000));
      }
    }

    const visible = () => {
      clearTimeout(timer);
      request?.abort();
      if (!document.hidden) {
        pending = all;
        timer = setTimeout(() => void read(), 0);
      }
    };
    timer = setTimeout(() => void read(), 0);
    document.addEventListener("visibilitychange", visible);
    return () => {
      stopped = true;
      clearTimeout(timer);
      request?.abort();
      document.removeEventListener("visibilitychange", visible);
    };
  }, [enterprise, binding, enabled]);
}
