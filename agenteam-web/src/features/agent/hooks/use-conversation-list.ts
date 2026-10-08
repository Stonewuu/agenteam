"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useCallback, useEffect, useRef, useState} from "react";
import {errorMessage} from "@/lib/http/api-client";
import {type ConversationFilter, listConversations} from "../api/conversation-api";
import type {Conversation} from "../types/execution";

type EmployeeOption = { name: string; icon: string | null; color: string | null; updatedAt: string };

/** 侧栏只保留实际加载的页，搜索和分类改变时从第一页读取。 */
export function useConversationList(enterprise: string, enabled = true) {
  const uiText = useT();
  const [filter, setFilter] = useState<ConversationFilter>({query: "", status: "active", favorite: false, agentId: ""});
  const [items, setItems] = useState<Conversation[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [version, setVersion] = useState(0);
  const binding = JSON.stringify([enterprise, filter]);
  const [loadedBinding, setLoadedBinding] = useState("");
  const [loadedEnterprise, setLoadedEnterprise] = useState("");
  const [employeeOptions, setEmployeeOptions] = useState<{
    enterprise: string;
    values: Map<string, EmployeeOption>
  }>({enterprise, values: new Map()});
  const controller = useRef<AbortController | null>(null);
  const loadingMore = useRef(false);
  const refresh = useCallback(() => setVersion((value) => value + 1), []);
  // 保留当前企业已经加载过的员工选项，搜索无结果时仍可调整员工条件。
  const rememberEmployees = useCallback((conversations: Conversation[]) => {
    setEmployeeOptions((previous) => {
      const values = new Map(previous.enterprise === enterprise ? previous.values : []);
      for (const conversation of conversations) {
        const previousEmployee = conversation.agentId ? values.get(conversation.agentId) : null;
        if (conversation.agentId && (!previousEmployee || conversation.updatedAt >= previousEmployee.updatedAt)) {
          values.set(conversation.agentId, {
            name: conversation.agentName,
            icon: conversation.agentIcon,
            color: conversation.agentColor,
            updatedAt: conversation.updatedAt
          });
        }
      }
      return {enterprise, values};
    });
  }, [enterprise]);
  useEffect(() => {
    if (!enabled) {
      return;
    }
    const request = new AbortController();
    controller.current = request;
    const timer = window.setTimeout(() => {
      setLoading(true);
      setError("");
      setCursor(null);
      setLoadedBinding(binding);
      listConversations(enterprise, filter, null, request.signal).then((page) => {
        if (!request.signal.aborted) {
          setLoadedEnterprise(enterprise);
          setItems(page.items);
          rememberEmployees(page.items);
          setCursor(page.hasMore ? page.nextCursor : null);
        }
      }).catch((failure) => {
        if (!request.signal.aborted) {
          setError(errorMessage(failure, "对话列表加载失败。"));
        }
      })
        .finally(() => {
          if (!request.signal.aborted) {
            setLoading(false);
          }
        });
    }, 300);
    return () => {
      request.abort();
      window.clearTimeout(timer);
    };
  }, [enterprise, filter, version, binding, enabled, rememberEmployees]);

  async function more() {
    const request = controller.current;
    if (loadedBinding !== binding || loading || !cursor || !request || request.signal.aborted || loadingMore.current) {
      return;
    }
    loadingMore.current = true;
    setLoading(true);
    setError("");
    try {
      const page = await listConversations(enterprise, filter, cursor, request.signal);
      if (!request.signal.aborted) {
        setItems((previous) => {
          const ids = new Set(previous.map((value) => value.id));
          return [...previous, ...page.items.filter((value) => !ids.has(value.id))];
        });
        rememberEmployees(page.items);
        setCursor(page.hasMore ? page.nextCursor : null);
      }
    } catch (failure) {
      if (!request.signal.aborted) {
        setError(errorMessage(failure, uiText("对话列表加载失败。")));
      }
    } finally {
      loadingMore.current = false;
      if (!request.signal.aborted) {
        setLoading(false);
      }
    }
  }

  const matches = loadedBinding === binding;
  return {
    items: enabled && loadedEnterprise === enterprise ? items : [],
    filter,
    setFilter,
    loading: enabled && (!matches || loading),
    error: enabled && matches ? error : "",
    employeeOptions: enabled && employeeOptions.enterprise === enterprise ? employeeOptions.values : new Map<string, EmployeeOption>(),
    hasMore: matches && cursor !== null,
    more,
    refresh
  };
}
