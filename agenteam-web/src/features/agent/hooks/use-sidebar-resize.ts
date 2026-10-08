"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type PointerEvent, type RefObject, useEffect, useRef} from "react";
import {sidebarDragWidth, sidebarReleaseLayout} from "../lib/conversation-sidebar-layout";

/** 拖动只更新布局属性，每帧最多一次；不让正文和文件预览随指针重复执行 React 渲染。 */
export function useSidebarResize(container: RefObject<HTMLDivElement | null>, width: number, maximumWidth: number,
                                 open: boolean, onResizeEnd: (width: number) => void) {
  const uiText = useT();
  const drag = useRef<{
    pointer: number;
    x: number;
    width: number;
    next: number;
    previousWidth: number;
    previousFull: string;
    handle: HTMLDivElement
  } | null>(null);
  const frame = useRef(0);
  const stopSettling = useRef<(() => void) | null>(null);

  function paint() {
    frame.current = 0;
    const current = drag.current, shell = container.current;
    if (!current || !shell) {
      return;
    }
    const next = sidebarDragWidth(current.next, maximumWidth);
    shell.style.setProperty("--conversation-sidebar-width", next + "px");
    current.handle.setAttribute("aria-valuenow", String(next));
    current.handle.setAttribute("aria-valuetext", next + uiText(" 像素"));
  }

  function finish(event: PointerEvent<HTMLDivElement>, commit: boolean) {
    const current = drag.current, shell = container.current;
    if (!current || current.pointer !== event.pointerId || !shell) {
      return;
    }
    cancelAnimationFrame(frame.current);
    paint();
    const panel = current.handle.parentElement!;
    // 先确认鼠标停下时的实际宽度，再恢复过渡；否则两次样式更新会合并成一次跳变。
    panel.getBoundingClientRect();
    drag.current = null;
    frame.current = 0;
    shell.removeAttribute("data-sidebar-resizing");
    panel.getBoundingClientRect();
    const target = commit ? sidebarReleaseLayout(current.next, maximumWidth)
      : {width: current.previousWidth, full: current.previousFull === "true"};
    shell.setAttribute("data-sidebar-settling", "true");
    shell.style.setProperty("--conversation-sidebar-width", target.width + "px");
    shell.setAttribute("data-sidebar-full", String(target.full));
    current.handle.setAttribute("aria-valuenow", String(target.width));
    current.handle.setAttribute("aria-valuetext", target.full ? uiText("文件侧栏已完全展开") : target.width + uiText(" 像素"));
    const clear = () => {
      window.clearTimeout(timer);
      panel.removeEventListener("transitionend", ended);
      shell.removeAttribute("data-sidebar-settling");
      stopSettling.current = null;
    };
    const ended = (transition: TransitionEvent) => {
      if (transition.target === panel && transition.propertyName === "flex-basis") {
        clear();
      }
    };
    const timer = window.setTimeout(clear, 260);
    panel.addEventListener("transitionend", ended);
    stopSettling.current = clear;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      clear();
    }
    if (commit) {
      onResizeEnd(current.next);
    }
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  useEffect(() => {
    const shell = container.current;
    return () => {
      cancelAnimationFrame(frame.current);
      stopSettling.current?.();
      frame.current = 0;
      drag.current = null;
      if (shell) {
        shell.removeAttribute("data-sidebar-resizing");
      }
    };
  }, [container, open]);

  return {
    onPointerDown: (event: PointerEvent<HTMLDivElement>) => {
      const shell = container.current;
      if (event.button !== 0 || !shell || drag.current) {
        return;
      }
      event.preventDefault();
      event.currentTarget.focus({preventScroll: true});
      event.currentTarget.setPointerCapture(event.pointerId);
      stopSettling.current?.();
      const actualWidth = event.currentTarget.parentElement!.getBoundingClientRect().width;
      shell.setAttribute("data-sidebar-resizing", "true");
      shell.style.setProperty("--conversation-sidebar-width", actualWidth + "px");
      drag.current = {
        pointer: event.pointerId, x: event.clientX, width: actualWidth, next: actualWidth, previousWidth: width,
        previousFull: shell.dataset.sidebarFull ?? "false", handle: event.currentTarget
      };
    },
    onPointerMove: (event: PointerEvent<HTMLDivElement>) => {
      const current = drag.current;
      if (!current || current.pointer !== event.pointerId) {
        return;
      }
      current.next = sidebarDragWidth(current.width + current.x - event.clientX, maximumWidth);
      if (!frame.current) {
        frame.current = requestAnimationFrame(paint);
      }
    },
    onPointerUp: (event: PointerEvent<HTMLDivElement>) => finish(event, true),
    onPointerCancel: (event: PointerEvent<HTMLDivElement>) => finish(event, false),
    onLostPointerCapture: (event: PointerEvent<HTMLDivElement>) => finish(event, false),
  };
}
