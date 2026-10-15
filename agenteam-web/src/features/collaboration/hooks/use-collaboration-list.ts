"use client";

import {useCallback, useState, useSyncExternalStore} from "react";
import {useApiQuery, type ApiPage} from "@/lib/http/use-api-query";
import {collaborationPath, type CollaborationTask} from "../types/collaboration";
import {changeListCriteria, emptyListState, listStateKey, readListState, type CollaborationListState} from "../lib/collaboration-list-state";

const savedStates = new Map<string, CollaborationListState>();
const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function snapshot(key: string) {
  const cached = savedStates.get(key);
  if (cached) {
    return cached;
  }
  let value = emptyListState;
  try {
    value = readListState(JSON.parse(window.sessionStorage.getItem(key) ?? "null"));
  } catch {
    // 浏览器不允许保存偏好时，当前页面仍可正常使用。
  }
  savedStates.set(key, value);
  return value;
}

function remember(key: string, state: CollaborationListState) {
  savedStates.set(key, state);
  try {
    window.sessionStorage.setItem(key, JSON.stringify(state));
  } catch {
    // 当前浏览器会话中的返回操作仍使用内存保存的条件。
  }
  listeners.forEach(listener => listener());
}

export function useCollaborationList(enterprise: string, user: string) {
  const key = listStateKey(enterprise, user);
  const read = useCallback(() => snapshot(key), [key]);
  const state = useSyncExternalStore(subscribe, read, () => emptyListState);
  const [refresh, setRefresh] = useState(0);
  const params = new URLSearchParams({limit: "30"});
  if (state.query.trim()) {
    params.set("query", state.query.trim());
  }
  if (state.status) {
    params.set("status", state.status);
  }
  if (state.cursor) {
    params.set("cursor", state.cursor);
  }
  const query = useApiQuery<ApiPage<CollaborationTask>>(`${collaborationPath(enterprise)}?${params}`, refresh, 300, true);
  return {...query, state, previous: state.previous, filtered: Boolean(state.query || state.status),
    criteria: (values: Partial<Pick<CollaborationListState, "query" | "status">>) => remember(key, changeListCriteria(state, values)),
    clear: () => remember(key, emptyListState),
    first: () => remember(key, {...state, cursor: null, previous: [], scrollTop: 0}),
    refresh: () => setRefresh(value => value + 1),
    saveScroll: (scrollTop: number) => remember(key, {...snapshot(key), scrollTop}),
    next: () => {
      if (!query.loading && query.data?.hasMore && query.data.nextCursor) {
        remember(key, {...state, cursor: query.data.nextCursor, previous: [...state.previous, state.cursor], scrollTop: 0});
      }
    },
    back: () => {
      if (!query.loading && state.previous.length) {
        remember(key, {...state, cursor: state.previous.at(-1) ?? null, previous: state.previous.slice(0, -1), scrollTop: 0});
      }
    },
  };
}
