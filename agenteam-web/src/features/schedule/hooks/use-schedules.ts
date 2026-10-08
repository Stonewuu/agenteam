"use client";


import {useEffect, useState} from "react";
import {ApiError, apiRequest, errorMessage} from "@/lib/http/api-client";
import type {ApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {Schedule} from "../types/schedule";

/** 接口只支持名称查询，先读完各页，再按真实启用状态筛选。 */
export function useSchedules(enterpriseId: string, query: string) {
  const [refresh, setRefresh] = useState(0);
  const [result, setResult] = useState<{ enterpriseId: string; query: string; items: Schedule[] } | null>(null);
  const [request, setRequest] = useState({query, loading: true, error: ""});
  useEffect(() => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setRequest({query, loading: true, error: ""});

      async function load() {
        try {
          const found = new Map<string, Schedule>();
          const cursors = new Set<string>();
          let cursor: string | null = null;
          do {
            const params = new URLSearchParams({query, limit: "100"});
            if (cursor) {
              params.set("cursor", cursor);
            }
            const page = await apiRequest<ApiPage<Schedule>>(organizationPath(enterpriseId, `/schedules?${params}`), {signal: controller.signal});
            for (const item of page.items) {
              found.set(item.id, item);
            }
            if (!page.hasMore) {
              break;
            }
            if (!page.nextCursor || cursors.has(page.nextCursor)) {
              throw new Error("暂时无法读取后续计划，请重新加载。");
            }
            cursors.add(page.nextCursor);
            cursor = page.nextCursor;
          } while (!controller.signal.aborted);
          if (!controller.signal.aborted) {
            setResult({enterpriseId, query, items: [...found.values()]});
            setRequest({query, loading: false, error: ""});
          }
        } catch (failure) {
          if (controller.signal.aborted) {
            return;
          }
          console.error("读取计划列表失败", {enterpriseId}, failure);
          if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
            setResult(null);
          }
          setRequest({query, loading: false, error: errorMessage(failure)});
        }
      }

      void load();
    }, 300);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [enterpriseId, query, refresh]);
  const hasActive = Boolean(result?.enterpriseId === enterpriseId && result.items.some(item => item.activeOccurrenceId));
  const hasUpcoming = Boolean(result?.enterpriseId === enterpriseId && result.items.some(item => item.enabled && item.nextRunAt));
  useEffect(() => {
    if ((!hasActive && !hasUpcoming) || request.loading || request.error) {
      return;
    }
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        setRefresh(value => value + 1);
      }
    }, hasActive ? 3000 : 10000);
    return () => window.clearInterval(timer);
  }, [hasActive, hasUpcoming, request.loading, request.error, refresh]);
  return {
    data: result?.enterpriseId === enterpriseId ? result.items : null,
    loading: request.query !== query || request.loading,
    error: request.query === query ? request.error : "",
    retry: () => setRefresh((value) => value + 1)
  };
}
