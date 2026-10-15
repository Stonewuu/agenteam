"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {ApiError, errorMessage} from "@/lib/http/api-client";
import {listConversationFiles} from "../api/conversation-files";
import type {ConversationFile, ConversationFileScope} from "../types/conversation-files";

type Listing = { key: string; items: ConversationFile[]; nextCursor: string | null; loaded: boolean; loading: boolean; error: string };

/** 列表只保存元数据；隐藏时停止请求，旧会话的晚到响应不会覆盖当前列表。 */
export function useConversationFiles(enterprise: string, conversation: string | null, scope: ConversationFileScope, path: string, active: boolean, running: boolean, refreshKey: string) {
  const key = enterprise + ":" + conversation + ":" + scope + ":" + path;
  const [value, setValue] = useState<Listing | null>(null);
  const [version, setVersion] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const request = useRef<AbortController | null>(null);
  const more = useRef<AbortController | null>(null);
  const loadedPages = useRef({key, count: 1});
  const reload = useCallback(() => setVersion((previous) => previous + 1), []);

  useEffect(() => {
    if (!active || !conversation) {
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    more.current?.abort();
    more.current = null;
    setLoadingMore(false);
    if (loadedPages.current.key !== key) {
      loadedPages.current = {key, count: 1};
    }
    const load = async () => {
      setValue((previous) => ({
        key,
        items: previous?.key === key ? previous.items : [],
        nextCursor: previous?.key === key ? previous.nextCursor : null,
        loaded: previous?.key === key && previous.loaded,
        loading: true,
        error: previous?.key === key ? previous.error : ""
      }));
      try {
        // 刷新用户已经展开的范围，全部读取成功后一起替换，避免后续页在刷新时消失。
        const items: ConversationFile[] = [];
        let cursor: string | null = null;
        let pages = 0;
        do {
          const page = await listConversationFiles(enterprise, conversation, scope, path, cursor, controller.signal);
          if (controller.signal.aborted) {
            return;
          }
          items.push(...page.items);
          cursor = page.nextCursor;
          pages += 1;
        } while (cursor && pages < loadedPages.current.count);
        if (!controller.signal.aborted) {
          loadedPages.current = {key, count: pages};
          const unique = [...new Map(items.map((file) => [file.id, file])).values()];
          setValue((previous) => ({
            key,
            items: previous?.key === key && JSON.stringify(previous.items) === JSON.stringify(unique) ? previous.items : unique,
            nextCursor: cursor,
            loaded: true,
            loading: false,
            error: ""
          }));
        }
      } catch (failure) {
        if (!controller.signal.aborted) {
          console.error("读取会话文件列表失败", {conversationId: conversation, error: failure});
          const inaccessible = failure instanceof ApiError && [401, 403, 404].includes(failure.status);
          if (inaccessible) {
            loadedPages.current = {key, count: 1};
          }
          setValue((previous) => ({
            key,
            items: !inaccessible && previous?.key === key ? previous.items : [],
            nextCursor: !inaccessible && previous?.key === key ? previous.nextCursor : null,
            loaded: !inaccessible && previous?.key === key && previous.loaded,
            loading: false,
            error: errorMessage(failure)
          }));
        }
      }
    };
    void load();
    return () => {
      controller.abort();
      more.current?.abort();
    };
  }, [enterprise, conversation, scope, path, active, key, version, refreshKey]);

  useEffect(() => {
    if (!active || !running || (value?.key === key && value.items.length > 100)) {
      return;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden && !more.current && !value?.loading) {
        reload();
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [active, running, reload, key, value?.key, value?.items.length, value?.loading]);

  const loadMore = async () => {
    if (!active || !conversation || value?.key !== key || !value.nextCursor || value.loading || more.current) {
      return;
    }
    const controller = new AbortController();
    more.current = controller;
    setLoadingMore(true);
    try {
      const page = await listConversationFiles(enterprise, conversation, scope, path, value.nextCursor, controller.signal);
      if (!controller.signal.aborted && !request.current?.signal.aborted) {
        loadedPages.current = {key, count: loadedPages.current.count + 1};
        setValue((previous) => {
          if (previous?.key !== key) {
            return previous;
          }
          const ids = new Set(previous.items.map((file) => file.id));
          return {
            ...previous,
            items: [...previous.items, ...page.items.filter((file) => !ids.has(file.id))],
            nextCursor: page.nextCursor,
            error: ""
          };
        });
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        console.error("读取更多会话文件失败", {conversationId: conversation, error: failure});
        const inaccessible = failure instanceof ApiError && [401, 403, 404].includes(failure.status);
        if (inaccessible) {
          loadedPages.current = {key, count: 1};
        }
        setValue((previous) => previous?.key === key ? {...previous,
          ...(inaccessible ? {items: [], nextCursor: null, loaded: false} : {}),
          error: errorMessage(failure)} : previous);
      }
    } finally {
      if (more.current === controller) {
        more.current = null;
        setLoadingMore(false);
      }
    }
  };

  return {
    items: value?.key === key ? value.items : [],
    loading: value?.key === key ? value.loading : active,
    initialLoading: active && Boolean(conversation) && (value?.key !== key || !value.loaded && value.loading),
    error: value?.key === key ? value.error : "",
    hasMore: value?.key === key && Boolean(value.nextCursor),
    loadingMore,
    reload,
    loadMore
  };
}
