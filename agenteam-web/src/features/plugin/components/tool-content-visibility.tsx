"use client";

import {type ReactNode, useCallback, useEffect, useRef, useState} from "react";
import {ToolContentPermit} from "./tool-content-permit";

/** 保留离屏内容的实际高度，卸载较重的详情；焦点或文本选择所在区域继续保留。 */
export function ToolContentVisibility({children}: { children: (ready: () => void) => ReactNode }) {
  const element = useRef<HTMLDivElement>(null);
  const [state, setState] = useState({visible: false, ready: false, height: 40});
  const ready = useCallback(() => setState((previous) => previous.ready ? previous : {...previous, ready: true}), []);
  useEffect(() => {
    const target = element.current;
    if (!target) {
      return;
    }
    let visible = false;
    const update = () => {
      if (visible) {
        setState((previous) => previous.visible ? previous : {...previous, visible: true, ready: false});
      } else {
        const selection = window.getSelection();
        if (target.contains(document.activeElement) || selection && !selection.isCollapsed && selection.anchorNode && target.contains(selection.anchorNode)) {
          return;
        }
        const height = target.getBoundingClientRect().height;
        setState((previous) => previous.visible ? {visible: false, ready: false, height} : previous);
      }
    };
    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      update();
    }, {rootMargin: "600px"});
    observer.observe(target);
    document.addEventListener("selectionchange", update);
    target.addEventListener("focusout", update);
    return () => {
      observer.disconnect();
      document.removeEventListener("selectionchange", update);
      target.removeEventListener("focusout", update);
    };
  }, []);
  return <div ref={element}
              style={{minHeight: !state.visible || !state.ready ? state.height : undefined}}>{state.visible &&
    <ToolContentPermit>{children(ready)}</ToolContentPermit>}</div>;
}
