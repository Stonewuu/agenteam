"use client";

import {useCallback, useEffect, useRef, useState} from "react";
import {errorMessage} from "@/lib/http/api-client";
import {listConversationFiles} from "../api/conversation-files";
import type {ConversationFile, ConversationFileScope} from "../types/conversation-files";

type Listing = { key: string; items: ConversationFile[]; nextCursor: string | null; loading: boolean; error: string };

/** 列表只保存元数据；隐藏时停止请求，旧会话的晚到响应不会覆盖当前列表。 */
export function useConversationFiles(enterprise: string, conversation: string | null, scope: ConversationFileScope, path: string, active: boolean, running: boolean, refreshKey: string) {
  const key = enterprise + ":" + conversation + ":" + scope + ":" + path;
  const [value, setValue] = useState<Listing | null>(null);
  const [version, setVersion] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  const request = useRef<AbortController | null>(null);
  const more = useRef<AbortController | null>(null);
  const reload = useCallback(() => setVersion((previous) => previous + 1), []);

  useEffect(() => {
    if (!active || !conversation) {
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    more.current?.abort();
    const load = async () => {
      setValue((previous) => ({
        key,
        items: previous?.key === key ? previous.items : [],
        nextCursor: null,
        loading: true,
        error: ""
      }));
      try {
        const page = await listConversationFiles(enterprise, conversation, scope, path, null, controller.signal);
        if (!controller.signal.aborted) {
          setValue({key, items: page.items, nextCursor: page.nextCursor, loading: false, error: ""});
        }
      } catch (failure) {
        if (!controller.signal.aborted) {
          console.error("读取会话文件列表失败", {conversationId: conversation, error: failure});
          setValue((previous) => ({
            key,
            items: previous?.key === key ? previous.items : [],
            nextCursor: null,
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
      if (!document.hidden && !more.current) {
        reload();
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [active, running, reload, key, value?.key, value?.items.length]);

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
        setValue((previous) => previous?.key === key ? {...previous, error: errorMessage(failure)} : previous);
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
    error: value?.key === key ? value.error : "",
    hasMore: value?.key === key && Boolean(value.nextCursor),
    loadingMore,
    reload,
    loadMore
  };
}
