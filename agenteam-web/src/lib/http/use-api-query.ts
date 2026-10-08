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

export function useApiQuery<T>(path: string | null, refresh: string | number = 0, delay = 0, keepPrevious = false,
                               options: { timeoutMs?: number } = {}) {
  const timeoutMs = options.timeoutMs;
  const [result, setResult] = useState<{ path: string; data: T } | null>(null);
  const [request, setRequest] = useState<{ path: string | null; loading: boolean; error: string }>({
    path,
    loading: true,
    error: ""
  });
  const [retry, setRetry] = useState(0);
  const requested = useRef(false);
  useEffect(() => {
    if (!path) {
      return;
    }
    const controller = new AbortController();
    // 首次打开直接读取；已有页面上的连续输入仍按调用方设置合并请求。
    const requestDelay = requested.current ? delay : 0;
    const timer = window.setTimeout(() => {
      requested.current = true;
      setRequest({path, loading: true, error: ""});
      apiRequest<T>(path, {signal: controller.signal, timeoutMs}).then((data) => {
        if (controller.signal.aborted) {
          return;
        }
        setResult({path, data});
        setRequest({path, loading: false, error: ""});
      }).catch((error) => {
        if (controller.signal.aborted) {
          return;
        }
        if (error instanceof ApiError && [401, 403, 404].includes(error.status)) {
          setResult(null);
        }
        setRequest({path, loading: false, error: errorMessage(error)});
      });
    }, requestDelay);
    return () => {
      controller.abort();
      window.clearTimeout(timer);
    };
  }, [path, refresh, retry, delay, timeoutMs]);
  const sameCollection = keepPrevious && path && result && collectionPath(result.path) === collectionPath(path);
  return {
    data: result && (result.path === path || sameCollection) ? result.data : null,
    loading: Boolean(path) && (request.path !== path || request.loading),
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
