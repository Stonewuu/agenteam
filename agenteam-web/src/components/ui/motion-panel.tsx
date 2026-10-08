"use client";

import {type HTMLAttributes, useEffect, useRef} from "react";

// 保留面板内的组件状态，仅在切换分类时播放轻量进入效果。
export function MotionPanel({
                              value,
                              ...props
                            }: HTMLAttributes<HTMLDivElement> & { value: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = ref.current;
    if (
      !element ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ) {
      return;
    }
    const style = getComputedStyle(element);
    const duration =
      parseFloat(style.getPropertyValue("--motion-content-duration")) || 160;
    const animation = element.animate(
      [
        {opacity: 0, transform: "translateY(4px)"},
        {opacity: 1, transform: "translateY(0)"},
      ],
      {
        duration,
        easing: style.getPropertyValue("--motion-ease").trim() || "ease-out",
      },
    );
    return () => animation.cancel();
  }, [value]);
  return <div ref={ref} {...props} />;
}
