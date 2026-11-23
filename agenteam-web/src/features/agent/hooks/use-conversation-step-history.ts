"use client";


import {useEffect, useMemo, useState} from "react";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import type {ApiPage} from "@/lib/http/use-api-query";
import {runPath} from "../api/conversation-api";
import type {ConversationMessage, RunAttempt, RunStep, SnapshotStepHistory} from "../types/execution";
import {validateRunStep, validateRunStepTree} from "../state/conversation-protocol";
import type {RunStepHistory} from "../lib/conversation-steps";

/** 每次读取快照后优先复用快照随附的步骤历史；仅补齐缺失或超出单批上限的历史轮次。 */
export function useConversationStepHistory(enterprise: string, messages: readonly ConversationMessage[] | undefined,
                                           refresh: number, sequence: string,
                                           initialHistory?: Record<string, SnapshotStepHistory>) {
  const runIds = JSON.stringify([...new Set(messages?.filter((message) => message.role === "assistant" && message.runId).map((message) => message.runId) ?? [])]);
  const [history, setHistory] = useState<{
    enterprise: string;
    values: ReadonlyMap<string, RunStepHistory>
  }>({enterprise, values: new Map()});
  const [retryState, setRetryState] = useState({refresh, count: 0});
  const forceFetch = retryState.refresh === refresh && retryState.count > 0;

  const embedded = useMemo(() => {
    const map = new Map<string, RunStepHistory>();
    if (!initialHistory) {
      return map;
    }
    for (const [id, entry] of Object.entries(initialHistory)) {
      if (!entry.hasMore) {
        map.set(id, {steps: entry.steps, attempts: entry.attempts, sequence, error: ""});
      }
    }
    return map;
  }, [initialHistory, sequence]);

  useEffect(() => {
    const allIds = JSON.parse(runIds) as string[];
    const queue = forceFetch ? allIds : allIds.filter((id) => !embedded.has(id));
    if (!queue.length) {
      return;
    }
    const controller = new AbortController();
    const update = (id: string, value: RunStepHistory) => {
      if (!controller.signal.aborted) {
        setHistory((current) => ({
          enterprise,
          values: new Map(current.enterprise === enterprise ? current.values : []).set(id, value)
        }));
      }
    };
    const read = async () => {
      while (queue.length && !controller.signal.aborted) {
        const id = queue.shift()!;
        try {
          const [steps, attempts] = await Promise.all([
            loadSteps(enterprise, id, controller.signal),
            apiRequest<RunAttempt[]>(`${runPath(enterprise, id)}/attempts`, {signal: controller.signal}),
          ]);
          update(id, {steps, attempts, sequence, error: ""});
        } catch (failure) {
          if (controller.signal.aborted) {
            return;
          }
          console.error("Failed to read conversation steps", {runId: id}, failure);
          update(id, {steps: [], attempts: [], sequence, error: errorMessage(failure, "执行过程暂时无法读取。")});
        }
      }
    };
    void Promise.all([read(), read(), read()]);
    return () => controller.abort();
    // 逐字输出不重新查询历史；完整快照变化或手动重试时才读取。
  }, [enterprise, runIds, refresh, retryState.count, forceFetch, sequence, embedded]);

  const combined = useMemo(() => {
    const fetched = history.enterprise === enterprise ? history.values : new Map<string, RunStepHistory>();
    if (!embedded.size) {
      return fetched;
    }
    const merged = new Map(embedded);
    for (const [id, value] of fetched) {
      if (forceFetch || !merged.has(id)) {
        merged.set(id, value);
      }
    }
    return merged;
  }, [enterprise, history, embedded, forceFetch]);

  return {
    history: combined,
    retry: () => setRetryState((current) => ({refresh, count: current.refresh === refresh ? current.count + 1 : 1}))
  };
}

async function loadSteps(enterprise: string, run: string, signal: AbortSignal) {
  const steps: RunStep[] = [];
  const seen = new Set<string>();
  let cursor: string | null = null;
  for (; ;) {
    const params = new URLSearchParams({limit: "100"});
    if (cursor) {
      params.set("cursor", cursor);
    }
    const page: ApiPage<RunStep> = await apiRequest(`${runPath(enterprise, run)}/steps?${params}`, {signal});
    page.items.forEach(validateRunStep);
    steps.push(...page.items);
    if (!page.hasMore) {
      validateRunStepTree(steps);
      return steps;
    }
    if (!page.nextCursor || seen.has(page.nextCursor)) {
      throw new Error("执行过程暂时无法完整读取，请重试。");
    }
    cursor = page.nextCursor;
    seen.add(cursor);
  }
}
