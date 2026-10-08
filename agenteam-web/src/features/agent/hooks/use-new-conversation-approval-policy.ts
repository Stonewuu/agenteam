"use client";

import {useCallback, useSyncExternalStore} from "react";
import type {ToolApprovalPolicy} from "../types/execution";

const preferenceEvent = "agenteam:new-conversation-approval-policy";
const initialPolicy: ToolApprovalPolicy = "auto_approve";
const temporary = new Map<string, ToolApprovalPolicy>();
const reported = new Set<string>();

function preferenceKey(userId: string, enterprise: string, prefix = "agenteam") {
  return `${prefix}:new-conversation-approval-policy:${encodeURIComponent(userId)}:${encodeURIComponent(enterprise)}`;
}

function parsePolicy(value: string | null): ToolApprovalPolicy {
  if (value === null) {
    return initialPolicy;
  }
  return value === "auto_approve" || value === "full_access" ? value : "default";
}

function reportOnce(key: string, operation: "read" | "write", error: unknown) {
  if (!reported.has(`${key}:${operation}`)) {
    reported.add(`${key}:${operation}`);
    console.error(operation === "read" ? "读取新对话权限模式偏好失败" : "保存新对话权限模式偏好失败", error);
  }
}

export function readNewConversationPolicy(userId: string, enterprise: string): ToolApprovalPolicy {
  const key = preferenceKey(userId, enterprise);
  if (temporary.has(key)) {
    return temporary.get(key)!;
  }
  if (typeof window === "undefined") {
    return initialPolicy;
  }
  try {
    const stored = window.localStorage.getItem(key)
      ?? window.localStorage.getItem(preferenceKey(userId, enterprise, "station"));
    return parsePolicy(stored);
  } catch (error) {
    reportOnce(key, "read", error);
    return initialPolicy;
  }
}

export function saveNewConversationPolicy(userId: string, enterprise: string, value: ToolApprovalPolicy) {
  const key = preferenceKey(userId, enterprise);
  const policy = parsePolicy(value);
  try {
    window.localStorage.setItem(key, policy);
    temporary.delete(key);
  } catch (error) {
    temporary.set(key, policy);
    reportOnce(key, "write", error);
  }
  window.dispatchEvent(new CustomEvent(preferenceEvent, {detail: key}));
}

/** 未保存偏好时选择自动批准；首页与新对话共享用户在当前企业最后的选择，已有对话沿用服务端保存值。 */
export function useNewConversationApprovalPolicy(userId: string, enterprise: string) {
  const key = preferenceKey(userId, enterprise);
  const legacyKey = preferenceKey(userId, enterprise, "station");
  const subscribe = useCallback((changed: () => void) => {
    const storage = (event: StorageEvent) => {
      if (event.key === key || event.key === legacyKey || event.key === null) {
        temporary.delete(key);
        changed();
      }
    };
    const local = (event: Event) => {
      if ((event as CustomEvent<string>).detail === key) {
        changed();
      }
    };
    window.addEventListener("storage", storage);
    window.addEventListener(preferenceEvent, local);
    return () => {
      window.removeEventListener("storage", storage);
      window.removeEventListener(preferenceEvent, local);
    };
  }, [key, legacyKey]);
  const snapshot = useCallback(() => readNewConversationPolicy(userId, enterprise), [userId, enterprise]);
  const value = useSyncExternalStore(subscribe, snapshot, () => initialPolicy);
  const change = useCallback((next: ToolApprovalPolicy) => saveNewConversationPolicy(userId, enterprise, next), [userId, enterprise]);
  return [value, change] as const;
}
