"use client";

import {useEffect, useMemo} from "react";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {ConversationNavigationItem} from "../types/execution";
import {conversationPath} from "../api/conversation-api";
import {navigationConversation, rolePreferenceKey, saveConversationPreference} from "../lib/conversation-navigation";

export function useCollaborationConversation(user: string, enterprise: string, conversation: string | null, task: string | null | undefined) {
  const query = useApiQuery<ConversationNavigationItem>(conversation && task ? `${conversationPath(enterprise, conversation)}/navigation` : null);
  const item = useMemo(() => query.data?.conversation.id === conversation ? navigationConversation(query.data) : null, [query.data, conversation]);
  const group = item?.collaboration;
  const retry = query.retry;
  useEffect(() => {
    const refresh = (event: Event) => {
      const detail = (event as CustomEvent<{enterpriseId: string; conversationId: string}>).detail;
      if (detail?.enterpriseId === enterprise && group?.roles.some(role => role.conversationId === detail.conversationId)) {
        retry();
      }
    };
    window.addEventListener("agenteam:conversation-changed", refresh);
    return () => window.removeEventListener("agenteam:conversation-changed", refresh);
  }, [enterprise, group, retry]);
  useEffect(() => {
    if (group && conversation && group.roles.some(role => role.conversationId === conversation)) {
      saveConversationPreference(rolePreferenceKey(user, enterprise, group.taskId), conversation);
    }
  }, [user, enterprise, conversation, group]);
  useEffect(() => {
    if (!group || ["completed", "failed", "cancelled"].includes(group.status)) {
      return;
    }
    const timer = window.setInterval(() => {
      if (!document.hidden) {
        retry();
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [group, retry]);
  return {...query, item, group};
}
