"use client";

import {useEffect, useRef, useState} from "react";
import {ApiError, apiRequest, errorMessage} from "./api-client";

export type ApiPage<T> = { items: T[]; nextCursor: string | null; hasMore: boolean };

/** 只有翻页位置可以不同，范围、团队与筛选条件都必须一致。 */
function collectionPath(path: string) {
  const [pathname, search] = path.split("?", 2);
  const params = new URLSearchParams(search);
  params.delete("cursor");
  params.sort();
  return `${pathname}?${params}`;
}

export type ApiQueryOptions<T> = {
  timeoutMs?: number;
  refreshInterval?: number | ((data: T | null) => number);
  revalidateOnFocus?: boolean;
};

/** 前台读取提供加载反馈；后台轮询保留内容，并在上次请求结束后再安排下一次。 */
export function useApiQuery<T>(path: string | null, refresh: string | number = 0, delay = 0, keepPrevious = false,
                               options: ApiQueryOptions<T> = {}) {
  const {timeoutMs, refreshInterval = 0, revalidateOnFocus = typeof refreshInterval === "function" || refreshInterval > 0} = options;
  const [result, setResult] = useState<{ path: string; data: T } | null>(null);
  const latest = useRef<{ path: string; data: T } | null>(null);
  const [request, setRequest] = useState<{ path: string | null; loading: boolean; fetching: boolean; error: string }>({
    path,
    loading: true,
    fetching: true,
    error: ""
  });
  const [retry, setRetry] = useState(0);
  const requested = useRef(false);
  useEffect(() => {
    if (!path) {
      return;
    }
    let controller: AbortController | null = null;
    let timer: number | undefined;
    let disposed = false;
    let inFlight = false;
    let failures = 0;
    let accessDenied = false;
    const available = () => document.visibilityState !== "hidden" && navigator.onLine !== false;
    const schedule = () => {
      const period = typeof refreshInterval === "function" ? refreshInterval(latest.current?.path === path ? latest.current.data : null) : refreshInterval;
      if (!disposed && !accessDenied && period > 0) {
        const interval = Math.min(Math.max(period, 1000) * 2 ** Math.min(failures, 4), 60000);
        timer = window.setTimeout(() => {
          if (available()) {
            void run(true);
          } else {
            schedule();
          }
        }, interval);
      }
    };
    const run = async (background: boolean) => {
      if (disposed || inFlight) {
        return;
      }
      window.clearTimeout(timer);
      inFlight = true;
      controller = new AbortController();
      const active = controller;
      requested.current = true;
      const loading = !background || latest.current?.path !== path;
      setRequest((previous) => ({path, loading, fetching: true, error: background ? previous.error : ""}));
      try {
        const data = await apiRequest<T>(path, {signal: active.signal, timeoutMs});
        if (disposed || active.signal.aborted) {
          return;
        }
        latest.current = {path, data};
        setResult(latest.current);
        failures = 0;
        setRequest({path, loading: false, fetching: false, error: ""});
      } catch (error) {
        if (disposed || active.signal.aborted) {
          return;
        }
        if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
          accessDenied = true;
          latest.current = null;
          setResult(null);
        }
        failures++;
        setRequest({path, loading: false, fetching: false, error: errorMessage(error)});
      } finally {
        inFlight = false;
        schedule();
      }
    };
    const resume = () => {
      if (available() && !accessDenied) {
        void run(true);
      }
    };
    // 首次打开直接读取；搜索等前台刷新仍使用调用方指定的等待时间。
    timer = window.setTimeout(() => void run(false), requested.current ? delay : 0);
    if (revalidateOnFocus) {
      document.addEventListener("visibilitychange", resume);
      window.addEventListener("focus", resume);
      window.addEventListener("online", resume);
    }
    return () => {
      disposed = true;
      controller?.abort();
      window.clearTimeout(timer);
      if (revalidateOnFocus) {
        document.removeEventListener("visibilitychange", resume);
        window.removeEventListener("focus", resume);
        window.removeEventListener("online", resume);
      }
    };
  }, [path, refresh, retry, delay, timeoutMs, refreshInterval, revalidateOnFocus]);
  const sameCollection = keepPrevious && path && result && collectionPath(result.path) === collectionPath(path);
  return {
    data: result && (result.path === path || sameCollection) ? result.data : null,
    loading: Boolean(path) && (request.path !== path || request.loading),
    fetching: Boolean(path) && (request.path !== path || request.fetching),
    error: request.path === path ? request.error : "",
    retry: () => setRetry((value) => value + 1)
  };
}

export function useApiPage<T>(path: string, refresh = 0, delay = 300, limit = 30, enabled = true) {
  const pageKey = `${path}|${limit}`;
  const [navigation, setNavigation] = useState<{
    path: string;
    cursor: string | null;
    previous: (string | null)[]
  }>({path, cursor: null, previous: []});
  const current = navigation.path === pageKey ? navigation : {path: pageKey, cursor: null, previous: []};
  const url = `${path}${path.includes("?") ? "&" : "?"}limit=${limit}${current.cursor ? `&cursor=${encodeURIComponent(current.cursor)}` : ""}`;
  const query = useApiQuery<ApiPage<T>>(enabled ? url : null, refresh, delay, true);
  return {
    ...query, previous: current.previous,
    first: () => setNavigation({path: pageKey, cursor: null, previous: []}),
    next: () => {
      if (query.data?.hasMore && !query.loading) {
        setNavigation({path: pageKey, cursor: query.data.nextCursor, previous: [...current.previous, current.cursor]});
      }
    },
    back: () => {
      if (current.previous.length) {
        setNavigation({
          path: pageKey,
          cursor: current.previous.at(-1) ?? null,
          previous: current.previous.slice(0, -1)
        });
      }
    },
  };
}
