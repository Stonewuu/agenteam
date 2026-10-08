"use client";

import {useLayoutEffect, useRef} from "react";

/** 消息为悬浮输入框预留实际高度，阅读旧消息时保持当前位置。 */
export function useComposerLayout() {
  const composer = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const panel = composer.current;
    const root = panel?.parentElement;
    const viewport = root?.querySelector<HTMLElement>("[data-conversation-viewport]");
    if (!panel || !root || !viewport) {
      return;
    }
    let previousHeight = -1;
    let previousViewportHeight = -1;
    const measure = () => {
      const height = Math.ceil(panel.getBoundingClientRect().height);
      const viewportHeight = viewport.clientHeight;
      if (height === previousHeight && viewportHeight === previousViewportHeight) {
        return;
      }
      previousHeight = height;
      previousViewportHeight = viewportHeight;
      root.style.setProperty("--composer-clearance", `${height + 24}px`);
      // 滚动由 useConversationScroll 观察正文尺寸后统一处理，测量过程不修改位置。
    };
    const observer = new ResizeObserver(measure);
    observer.observe(panel);
    observer.observe(viewport);
    measure();
    return () => {
      observer.disconnect();
      root.style.removeProperty("--composer-clearance");
    };
  }, []);
  return composer;
}
