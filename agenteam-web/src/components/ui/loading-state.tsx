"use client";

import styles from "./surface.module.css";

export function LoadingState({layout = "rows", label = "正在加载…"}: { layout?: "rows" | "cards"; label?: string }) {
  return <div className={`${styles.skeleton} ${layout === "cards" ? styles.skeletonCards : ""}`}
              data-loading-placeholder role="status" aria-label={label} aria-busy="true">
    {(layout === "cards" ? [0, 1, 2] : [0, 1]).map((index) => <div className={styles.skeletonItem} key={index}
                                                                   aria-hidden="true"><span
      className={styles.skeletonIcon}/><span className={styles.skeletonCopy}><i/><i/></span></div>)}
  </div>;
}
