"use client";

import {useEffect, useState} from "react";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import type {ApiPage} from "@/lib/http/use-api-query";
import {runPath} from "@/features/agent/api/conversation-api";
import type {RunStep} from "@/features/agent/types/execution";

/** 节点图读取完整步骤页，刷新后仍按实际节点编号显示状态。 */
export function useWorkflowSteps(enterprise: string, runId: string | null, active: boolean) {
  const [result, setResult] = useState<{
    runId: string;
    steps: RunStep[];
    error: string
  } | null>(null), [refresh, setRefresh] = useState(0);
  useEffect(() => {
    if (!runId) {
      return;
    }
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function read() {
      try {
        const steps: RunStep[] = [];
        let cursor: string | null = null;
        do {
          const page: ApiPage<RunStep> = await apiRequest(`${runPath(enterprise, runId!)}/steps?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`, {signal: controller.signal});
          steps.push(...page.items);
          cursor = page.hasMore ? page.nextCursor : null;
        } while (cursor && !controller.signal.aborted);
        if (!controller.signal.aborted) {
          setResult({runId: runId!, steps, error: ""});
        }
      } catch (failed) {
        if (!controller.signal.aborted) {
          setResult((previous) => ({
            runId: runId!,
            steps: previous?.runId === runId ? previous.steps : [],
            error: errorMessage(failed)
          }));
        }
      }
      if (!controller.signal.aborted && active) {
        timer = setTimeout(() => void read(), 1200);
      }
    }

    void read();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [enterprise, runId, active, refresh]);
  return {
    steps: result?.runId === runId ? result.steps : [],
    error: result?.runId === runId ? result.error : "",
    retry: () => setRefresh((value) => value + 1)
  };
}
