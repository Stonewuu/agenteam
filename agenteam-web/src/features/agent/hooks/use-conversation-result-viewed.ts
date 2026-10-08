"use client";

import {useEffect} from "react";
import {ApiError, apiRequest, reportRequestFailure} from "@/lib/http/api-client";
import {notificationsChanged} from "@/features/notification/components/notification-center-provider";
import {runPath} from "../api/conversation-api";
import type {Run} from "../types/execution";

/** 页面已显示成功结果且处于前台时，取消这次执行的完成提醒。 */
export function useConversationResultViewed(enterprise: string, conversation: string | null, run: Run | null | undefined, ready: boolean) {
  const runId = ready && run?.status === "completed" && run.mode !== "preview" && run.conversationId === conversation ? run.id : null;
  useEffect(() => {
    if (!runId) {
      return;
    }
    const controller = new AbortController();
    const path = `${runPath(enterprise, runId)}/result-viewed`;
    let acknowledged = false;
    let pending = false;
    let failures = 0;
    let timer: number | undefined;

    async function acknowledge() {
      if (controller.signal.aborted || acknowledged || pending || document.visibilityState !== "visible" || !document.hasFocus()) {
        return;
      }
      window.clearTimeout(timer);
      pending = true;
      try {
        await apiRequest<void>(path, {method: "POST", signal: controller.signal, timeoutMs: 5000});
        acknowledged = true;
        if (!controller.signal.aborted) {
          notificationsChanged();
        }
      } catch (error) {
        if (controller.signal.aborted) {
          return;
        }
        reportRequestFailure(error, "POST", path);
        if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
          acknowledged = true;
          return;
        }
        failures += 1;
        timer = window.setTimeout(() => void acknowledge(), Math.min(1000 * 2 ** Math.min(failures - 1, 5), 30000));
      } finally {
        pending = false;
      }
    }

    const onVisible = () => void acknowledge();
    // 留到页面提交后再确认，切换对话和开发模式的重复挂载可以先取消。
    timer = window.setTimeout(onVisible, 0);
    document.addEventListener("visibilitychange", onVisible);
    window.addEventListener("focus", onVisible);
    window.addEventListener("online", onVisible);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
      window.removeEventListener("focus", onVisible);
      window.removeEventListener("online", onVisible);
    };
  }, [enterprise, conversation, runId]);
}
