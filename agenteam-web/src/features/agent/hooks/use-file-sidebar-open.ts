"use client";

import {useCallback, useSyncExternalStore} from "react";

const changedEvent = "agenteam:file-sidebar-open-changed";
const temporaryValues = new Map<string, boolean>();
let storageWarningShown = false;

function reportStorageFailure(failure: unknown) {
  if (!storageWarningShown) {
    storageWarningShown = true;
    console.warn("无法保存文件侧栏开关状态，本次页面仍可正常使用。", failure);
  }
}

function readOpen(key: string) {
  const temporary = temporaryValues.get(key);
  if (temporary !== undefined) {
    return temporary;
  }
  try {
    return window.localStorage.getItem(key) === "true";
  } catch (failure) {
    reportStorageFailure(failure);
    return false;
  }
}

function subscribe(callback: () => void) {
  window.addEventListener("storage", callback);
  window.addEventListener(changedEvent, callback);
  return () => {
    window.removeEventListener("storage", callback);
    window.removeEventListener(changedEvent, callback);
  };
}

/** 只保存开关状态，宽度与全宽状态继续由当前会话管理。 */
export function useFileSidebarOpen(user: string, enterprise: string) {
  const key = "agenteam:file-sidebar-open:" + user + ":" + enterprise;
  const snapshot = useCallback(() => readOpen(key), [key]);
  const open = useSyncExternalStore(subscribe, snapshot, () => false);
  const setOpen = useCallback((value: boolean) => {
    try {
      window.localStorage.setItem(key, String(value));
      temporaryValues.delete(key);
    } catch (failure) {
      temporaryValues.set(key, value);
      reportStorageFailure(failure);
    }
    window.dispatchEvent(new Event(changedEvent));
  }, [key]);
  return [open, setOpen] as const;
}
