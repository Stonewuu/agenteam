"use client";

import {type RefObject, useCallback, useId, useLayoutEffect, useState} from "react";
import {sidebarLayout, sidebarReleaseLayout} from "../lib/conversation-sidebar-layout";
import {useConversationNavigation} from "../components/conversation-navigation-context";

type Preference = { width: number; full: boolean };
const defaultPreference: Preference = {width: 360, full: false};
const compactWidth = 880;

export function useConversationSidebar(user: string, enterprise: string, containerRef: RefObject<HTMLDivElement | null>, conversation: string | null) {
  const id = useId();
  const navigation = useConversationNavigation();
  const [localOpen, setLocalOpen] = useState(false);
  const [localTab, setLocalTab] = useState("files");
  // 开关和标签由不会随会话页面重建的导航层保留，宽度仍只属于当前会话。
  const open = navigation?.fileSidebarOpen ?? localOpen;
  const tab = navigation?.fileSidebarTab ?? localTab;
  const setOpen = navigation?.setFileSidebarOpen ?? setLocalOpen;
  const setTab = navigation?.setFileSidebarTab ?? setLocalTab;
  const scope = user + ":" + enterprise + ":" + (conversation ?? "new");
  const [saved, setSaved] = useState({scope, preference: defaultPreference});
  const preference = saved.scope === scope ? saved.preference : defaultPreference;
  if (saved.scope !== scope) {
    // 切换会话当次渲染就恢复默认宽度，避免先绘制前一个会话的全宽布局。
    setSaved({scope, preference: defaultPreference});
  }
  const [availableWidth, setAvailableWidth] = useState(0);

  useLayoutEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }
    element.setAttribute("data-sidebar-reset", "true");
    const frame = requestAnimationFrame(() => {
      element.querySelector("aside")?.getBoundingClientRect();
      element.removeAttribute("data-sidebar-reset");
    });
    return () => cancelAnimationFrame(frame);
  }, [containerRef, scope]);

  useLayoutEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }
    const frame = element.closest<HTMLElement>("[data-conversation-frame]");
    let measuredWidth = 0;
    let publishedWidth = 0;
    let animating = false;
    const publishWidth = () => {
      if (measuredWidth !== publishedWidth) {
        publishedWidth = measuredWidth;
        setAvailableWidth(measuredWidth);
      }
    };
    const frameTransition = (event: TransitionEvent) => {
      if (event.target !== frame || event.propertyName !== "grid-template-columns") {
        return;
      }
      animating = event.type === "transitionrun";
      if (!animating) {
        measuredWidth = element.getBoundingClientRect().width;
        publishWidth();
      }
    };
    frame?.addEventListener("transitionrun", frameTransition);
    frame?.addEventListener("transitionend", frameTransition);
    frame?.addEventListener("transitioncancel", frameTransition);
    const observer = new ResizeObserver((entries) => {
      measuredWidth = entries[0]?.contentRect.width ?? 0;
      // 列表动画由样式实时布局，避免每帧让整个对话和文件预览重新渲染。
      // 跨过窄屏边界时仍立即切换抽屉，不能等动画结束后挤压操作区。
      if (!animating || publishedWidth === 0 || (publishedWidth < compactWidth) !== (measuredWidth < compactWidth)) {
        publishWidth();
      }
    });
    observer.observe(element);
    return () => {
      observer.disconnect();
      frame?.removeEventListener("transitionrun", frameTransition);
      frame?.removeEventListener("transitionend", frameTransition);
      frame?.removeEventListener("transitioncancel", frameTransition);
    };
  }, [containerRef]);

  const update = useCallback((change: Partial<Preference>) => {
    setSaved((previous) => ({
      scope,
      preference: {...(previous.scope === scope ? previous.preference : defaultPreference), ...change}
    }));
  }, [scope]);

  const compact = availableWidth > 0 && availableWidth < compactWidth;
  const maximumWidth = availableWidth || 1200;
  const splitLimit = sidebarLayout(280, maximumWidth).maximumSplitWidth;
  const layout = sidebarLayout(Math.min(preference.width, splitLimit), maximumWidth, preference.full && !compact);
  const restoreConversation = useCallback(() => update({full: false}), [update]);
  const setWidth = useCallback((width: number) => {
    const next = sidebarLayout(width, maximumWidth);
    update(next.full ? {full: true} : {width: next.width, full: false});
  }, [maximumWidth, update]);
  const finishResize = useCallback((width: number) => {
    const next = sidebarReleaseLayout(width, maximumWidth);
    update(next.full ? {full: true} : {width: next.width, full: false});
  }, [maximumWidth, update]);
  useLayoutEffect(() => {
    const element = containerRef.current;
    if (element) {
      element.style.setProperty("--conversation-area-width", maximumWidth + "px");
      if (!element.hasAttribute("data-sidebar-resizing")) {
        element.style.setProperty("--conversation-sidebar-width", layout.width + "px");
        element.setAttribute("data-sidebar-full", String(layout.full && open && !compact));
      }
    }
  }, [containerRef, layout.width, layout.full, open, compact, maximumWidth]);
  return {
    id, compact, open, tab, full: layout.full,
    width: layout.width, maximumWidth, setOpen, setTab, setWidth, finishResize, restoreConversation
  };
}
