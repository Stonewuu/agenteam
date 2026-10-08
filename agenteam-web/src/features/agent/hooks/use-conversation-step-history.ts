"use client";


import {useEffect, useState} from "react";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import type {ApiPage} from "@/lib/http/use-api-query";
import {runPath} from "../api/conversation-api";
import type {ConversationMessage, RunAttempt, RunStep} from "../types/execution";
import {validateRunStep, validateRunStepTree} from "../state/conversation-protocol";
import type {RunStepHistory} from "../lib/conversation-steps";

/** 每次读取快照后补齐历史步骤；每个执行只读取一次，限制并行请求数量。 */
export function useConversationStepHistory(enterprise: string, messages: readonly ConversationMessage[] | undefined, refresh: number, sequence: string) {
  const runIds = JSON.stringify([...new Set(messages?.filter((message) => message.role === "assistant" && message.runId).map((message) => message.runId) ?? [])]);
  const [history, setHistory] = useState<{
    enterprise: string;
    values: ReadonlyMap<string, RunStepHistory>
  }>({enterprise, values: new Map()});
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    const queue = JSON.parse(runIds) as string[];
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
          console.error("读取对话步骤失败", {runId: id}, failure);
          update(id, {steps: [], attempts: [], sequence, error: errorMessage(failure, "执行过程暂时无法读取。")});
        }
      }
    };
    void Promise.all([read(), read(), read()]);
    return () => controller.abort();
    // 逐字输出不重新查询历史；完整快照变化或手动重试时才读取。
  }, [enterprise, runIds, refresh, retry, sequence]);
  return {
    history: history.enterprise === enterprise ? history.values : new Map<string, RunStepHistory>(),
    retry: () => setRetry((value) => value + 1)
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
