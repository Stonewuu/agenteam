"use client";

import {type ReactNode, useLayoutEffect, useRef} from "react";

/** 所有菜单分组共用选中背景，按实际位置移动，兼容滚动和手机布局。 */
export function SidebarNavigation({label, children}: { label: string; children: ReactNode }) {
  const navigation = useRef<HTMLElement>(null);
  const selection = useRef<HTMLSpanElement>(null);

  useLayoutEffect(() => {
    const element = navigation.current;
    const indicator = selection.current;
    if (!element || !indicator) {
      return;
    }
    const update = () => {
      const active = element.querySelector<HTMLElement>('.nav-item[aria-current="page"]');
      if (!active) {
        indicator.style.opacity = "0";
        return;
      }
      const bounds = active.getBoundingClientRect();
      const top = bounds.top - element.getBoundingClientRect().top + element.scrollTop;
      indicator.style.transform = `translateY(${top}px)`;
      indicator.style.height = `${bounds.height}px`;
      indicator.style.opacity = bounds.height > 0 ? "1" : "0";
    };

    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    for (const child of element.children) {
      if (child !== indicator) {
        observer.observe(child);
      }
    }
    return () => observer.disconnect();
  }, [children]);

  return <nav ref={navigation} className="sidebar-navigation" aria-label={label}>
    <span ref={selection} className="sidebar-selection" aria-hidden="true" style={{opacity: 0}}/>
    {children}
  </nav>;
}
