"use client";

import {useCallback, useSyncExternalStore} from "react";
import {conversationPreferenceChanged, readConversationPreference, saveConversationPreference} from "../lib/conversation-navigation";

export type ConversationScope = "all" | "standalone" | "collaboration";

function subscribe(callback: () => void) {
  window.addEventListener("storage", callback);
  window.addEventListener(conversationPreferenceChanged, callback);
  return () => {
    window.removeEventListener("storage", callback);
    window.removeEventListener(conversationPreferenceChanged, callback);
  };
}

export function useConversationScope(user: string, enterprise: string) {
  const key = `agenteam:conversation-scope:${user}:${enterprise}`;
  const snapshot = useCallback((): ConversationScope => {
    const value = readConversationPreference(key);
    return value === "standalone" || value === "collaboration" ? value : "all";
  }, [key]);
  const scope = useSyncExternalStore(subscribe, snapshot, () => "all" as ConversationScope);
  const setScope = useCallback((value: ConversationScope) => saveConversationPreference(key, value), [key]);
  return [scope, setScope] as const;
}
