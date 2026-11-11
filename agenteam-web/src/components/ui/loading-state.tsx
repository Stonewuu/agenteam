"use client";

import styles from "./surface.module.css";
import {useT} from "@/lib/i18n/locale-provider";

export function LoadingState({layout = "rows", label}: { layout?: "rows" | "cards"; label?: string }) {
  const t = useT();
  return <div className={`${styles.skeleton} ${layout === "cards" ? styles.skeletonCards : ""}`}
              data-loading-placeholder role="status" aria-label={label ?? t("正在加载…")} aria-busy="true">
    {(layout === "cards" ? [0, 1, 2] : [0, 1]).map((index) => <div className={styles.skeletonItem} key={index}
                                                                   aria-hidden="true"><span
      className={styles.skeletonIcon}/><span className={styles.skeletonCopy}><i/><i/></span></div>)}
  </div>;
}
