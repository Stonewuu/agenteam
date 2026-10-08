"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {useCallback, useEffect, useRef, useState} from "react";
import {ApiError, errorMessage} from "@/lib/http/api-client";
import {loadRun, loadSnapshot, olderMessages, readConversationEvents} from "../api/conversation-api";
import {
  applyConversationFrame,
  type ConversationState,
  fromSnapshot,
  streamSequence
} from "../state/conversation-event-state";
import {SnapshotRequired, validateMessage} from "../state/conversation-protocol";
import type {ConversationMessage, ConversationSnapshot} from "../types/execution";
import {fileToolActivity} from "../lib/conversation-file-activity";

/** 每次打开先取完整快照；重复、遗漏或版本不符的事件不会被追加到页面。 */
export function useConversationStream(enterprise: string, conversation: string | null) {
  const uiText = useT();
  const activityScope = enterprise + ":" + (conversation ?? "new");
  const [fileActivity, setFileActivity] = useState<{
    scope: string;
    key: string | null;
    refreshKey: string | null
  }>({scope: activityScope, key: null, refreshKey: null});
  if (fileActivity.scope !== activityScope) {
    // 切换会话时清除旧调用通知，返回历史会话不会因此自动打开侧栏。
    setFileActivity({scope: activityScope, key: null, refreshKey: null});
  }
  const [value, setValue] = useState<ConversationState | null>(null);
  const state = useRef<ConversationState | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [reloadVersion, setReloadVersion] = useState(0);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [historyVersion, setHistoryVersion] = useState(0);
  const [historySequence, setHistorySequence] = useState("0");
  const olderPending = useRef(false);
  const scope = useRef<AbortController | null>(null);
  const commit = useCallback((next: ConversationState) => {
    state.current = next;
    setValue(next);
  }, []);
  const reload = useCallback(() => setReloadVersion((current) => current + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    scope.current = controller;
    const current = () => !controller.signal.aborted;
    const denied = (failure: unknown) => {
      if (!(failure instanceof ApiError) || ![401, 403, 404].includes(failure.status)) {
        return false;
      }
      state.current = null;
      setValue(null);
      setError(errorMessage(failure));
      return true;
    };
    const show = (snapshot: ConversationSnapshot) => {
      const next = fromSnapshot(snapshot);
      if (snapshot.protocolVersion !== 2 && state.current?.snapshot.conversation.id === snapshot.conversation.id) {
        next.approvals = state.current.approvals;
        next.steps = state.current.steps;
      }
      if (!snapshot.activeRun && state.current?.latestRun?.conversationId === snapshot.conversation.id) {
        next.latestRun = state.current.latestRun;
      }
      commit(next);
      setHistorySequence(streamSequence(snapshot));
      setHistoryVersion((value) => value + 1);
      setLoading(false);
      setError("");
      return next;
    };

    async function refresh() {
      const snapshot = await loadSnapshot(enterprise, conversation!, controller.signal);
      if (!current()) {
        return null;
      }
      const next = show(snapshot);
      const last = snapshot.messages.findLast((message) => message.role === "assistant" && message.runId);
      if (!snapshot.activeRun && last?.runId) {
        const run = await loadRun(enterprise, last.runId, controller.signal);
        if (current() && state.current?.snapshot.conversation.id === conversation) {
          commit({...state.current, latestRun: run});
        }
      }
      return next;
    }

    async function receive() {
      if (!conversation) {
        state.current = null;
        setValue(null);
        setError("");
        setLoading(false);
        return;
      }
      setLoading(state.current?.snapshot.conversation.id !== conversation);
      setError("");
      let failures = 0;
      try {
        await refresh();
      } catch (failed) {
        if (current()) {
          setLoading(false);
          if (!denied(failed)) {
            setError(errorMessage(failed, "对话加载失败，请重试。"));
          }
        }
        return;
      }
      let initialRead = state.current?.snapshot.protocolVersion === 2;
      while (current() && state.current && (state.current.snapshot.activeRun || initialRead)) {
        try {
          const existing = state.current;
          if (existing.snapshot.protocolVersion === 2 && !existing.snapshot.streamCursor) {
            if (!existing.snapshot.activeRun) {
              return;
            }
            await wait(Math.min(1000 * 2 ** Math.min(failures++, 4), 15000), controller.signal);
            if (current()) {
              await refresh();
            }
            continue;
          }
          initialRead = false;
          commit({...existing, replaying: true});
          await readConversationEvents({
            enterprise, conversation, after: streamSequence(existing.snapshot),
            generation: existing.snapshot.streamCursor?.generation, signal: controller.signal, onFrame: (frame) => {
              if (!current() || !state.current) {
                return;
              }
              failures = 0;
              const previous = state.current;
              const next = applyConversationFrame(previous, frame, enterprise);
              commit(next);
              const activity = next !== previous ? fileToolActivity(previous, frame) : null;
              if (activity) {
                setFileActivity((current) => ({
                  scope: activityScope,
                  key: activity.openKey ?? (current.scope === activityScope ? current.key : null),
                  refreshKey: activity.refreshKey ?? (current.scope === activityScope ? current.refreshKey : null)
                }));
              }
            }
          });
          if (!current()) {
            return;
          }
          await refresh();
          if (!state.current?.snapshot.activeRun) {
            return;
          }
        } catch (failed) {
          if (!current()) {
            return;
          }
          if (denied(failed)) {
            return;
          }
          if (failed instanceof SnapshotRequired) {
            try {
              await refresh();
              if (!state.current?.snapshot.activeRun) {
                return;
              }
            } catch (refreshError) {
              if (denied(refreshError)) {
                return;
              }
              if (refreshError instanceof SnapshotRequired) {
                setError("当前对话内容暂时无法读取，请重新加载。");
                return;
              }
            }
          }
        }
        const delay = Math.min(1000 * 2 ** Math.min(failures++, 4), 15000) + Math.random() * 300;
        await wait(document.hidden ? Math.max(delay, 15000) : delay, controller.signal);
      }
    }

    const timer = window.setTimeout(() => void receive(), 0);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [enterprise, conversation, activityScope, reloadVersion, commit]);

  async function loadOlder() {
    const previous = state.current;
    const controller = scope.current;
    if (!conversation || !previous?.snapshot.nextBeforeMessageId || olderPending.current || !controller || controller.signal.aborted) {
      return;
    }
    olderPending.current = true;
    setLoadingOlder(true);
    setError("");
    try {
      const page = await olderMessages(enterprise, conversation, previous.snapshot.nextBeforeMessageId, controller.signal);
      page.messages.forEach(validateMessage);
      const current = state.current;
      if (controller.signal.aborted || !current || current.snapshot.conversation.id !== conversation) {
        return;
      }
      const ids = new Set(current.snapshot.messages.map((message) => message.id));
      commit({
        ...current, snapshot: {
          ...current.snapshot,
          messages: [...page.messages.filter((message) => !ids.has(message.id)), ...current.snapshot.messages],
          hasOlderMessages: page.hasMore,
          nextBeforeMessageId: page.nextBeforeMessageId
        }
      });
    } catch (failed) {
      if (!controller.signal.aborted) {
        setError(errorMessage(failed, uiText("更早的消息加载失败。")));
      }
    } finally {
      olderPending.current = false;
      setLoadingOlder(false);
    }
  }

  function setFeedback(id: string, feedback: ConversationMessage["feedback"]) {
    const current = state.current;
    if (current?.snapshot.conversation.id !== conversation) {
      return;
    }
    commit({
      ...current,
      snapshot: {
        ...current.snapshot,
        messages: current.snapshot.messages.map((message) => message.id === id ? {...message, feedback} : message)
      }
    });
  }

  return {
    state: value?.snapshot.conversation.id === conversation ? value : null,
    loading,
    error,
    reload,
    loadOlder,
    loadingOlder,
    setFeedback,
    historyVersion,
    historySequence,
    fileActivityKey: fileActivity.scope === activityScope ? fileActivity.key : null,
    fileRefreshKey: fileActivity.scope === activityScope ? fileActivity.refreshKey : null
  };
}

function wait(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const done = () => {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", done);
      resolve();
    };
    const timer = window.setTimeout(done, milliseconds);
    signal.addEventListener("abort", done, {once: true});
  });
}
