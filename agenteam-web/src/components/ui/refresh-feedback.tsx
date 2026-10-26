"use client";

import { type CSSProperties, useEffect, useSyncExternalStore } from "react";
import { IconRefresh } from "./icons";
import { useT } from "@/lib/i18n/locale-provider";
import { refreshFeedbackStore, refreshFeedbackTimings } from "./refresh-feedback-store";
import styles from "./refresh-feedback.module.css";

export function useRefreshFeedback(active: boolean) {
  useEffect(() => {
    if (active) {
      return refreshFeedbackStore.begin();
    }
  }, [active]);
}

/** 全站只保留一份刷新反馈，不受弹窗或列表内部的尺寸动画影响。 */
export function RefreshFeedback() {
  const t = useT();
  const state = useSyncExternalStore(
    refreshFeedbackStore.subscribe,
    refreshFeedbackStore.getSnapshot,
    refreshFeedbackStore.getServerSnapshot,
  );
  return (
    <div
      className={styles.indicator}
      data-refresh-feedback
      data-phase={state.phase}
      data-busy={state.busy}
      data-icon-context="inherit"
      role="status"
      aria-live="polite"
      aria-atomic="true"
      aria-hidden={state.phase === "hidden" || !state.busy}
      style={{ "--refresh-duration": `${refreshFeedbackTimings.fadeMs}ms` } as CSSProperties}
    >
      <IconRefresh size={16} className={styles.spinner} aria-hidden="true" />
      <span>{t("正在更新…")}</span>
    </div>
  );
}
