"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ReactNode, useEffect, useId, useSyncExternalStore} from "react";
import styles from "./tool-payload.module.css";

// 全页面最多同时保留 24 份详情，每份最多 6 个 32 KB 分片（含初始分片）。
// 这里只保留组件编号；正文属于已挂载组件，卸载后不会留在全局缓存。
const readers = new Set<string>();
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
const notify = () => listeners.forEach((listener) => listener());

export function ToolContentPermit({children}: { children: ReactNode }) {
  const uiText = useT();
  const id = useId();
  const allowed = useSyncExternalStore(subscribe, () => Array.from(readers).indexOf(id) >= 0 && Array.from(readers).indexOf(id) < 24, () => false);
  useEffect(() => {
    readers.add(id);
    notify();
    return () => {
      readers.delete(id);
      notify();
    };
  }, [id]);
  return allowed ? children : <div className={styles.contentNotice} role="status">{uiText("正在读取内容…")}</div>;
}
