"use client";

import {type ReactNode, useLayoutEffect, useRef} from "react";
import styles from "./animated-height.module.css";

/** 测量真实内容高度；包含表单控件时预留阴影空间，避免动画裁切聚焦边框。 */
export function AnimatedHeight({children, preserveControlShadows = false}: {
  children: ReactNode;
  preserveControlShadows?: boolean
}) {
  const outer = useRef<HTMLDivElement>(null);
  const inner = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const container = outer.current, content = inner.current;
    if (!container || !content) {
      return;
    }
    let measured = false;
    const measure = () => {
      if (!content.getClientRects().length) {
        return;
      }
      // 弹窗进场时会缩放，读取布局高度，避免把缩小后的视觉高度固定下来。
      const height = content.offsetHeight;
      container.style.height = `${height}px`;
      if (measured) {
        container.dataset.measured = "true";
      }
      measured = true;
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(content);
    return () => observer.disconnect();
  }, []);
  return <div ref={outer} className={styles.outer} style={preserveControlShadows ? {margin: "-6px"} : undefined}>
    <div ref={inner}
         className={`${styles.inner}${preserveControlShadows ? ` ${styles.controlSpace}` : ""}`}>{children}</div>
  </div>;
}
