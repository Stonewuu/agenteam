"use client";

import {
  type KeyboardEvent,
  type PointerEvent,
  type TouchEvent,
  type UIEvent,
  useLayoutEffect,
  useRef,
  type WheelEvent
} from "react";

/** 只控制思考内容内部的滚动；阅读旧内容时暂停跟随，回到底部后恢复。 */
export function useThinkingScroll(open: boolean, streaming: boolean) {
  const viewport = useRef<HTMLDivElement>(null);
  const following = useRef(streaming);
  const wasStreaming = useRef(streaming);
  const lastTop = useRef(0);
  const direction = useRef(0);
  const dragging = useRef(false);
  const touchY = useRef(0);

  function atBottom(element: HTMLDivElement) {
    return element.scrollHeight - element.scrollTop - element.clientHeight <= 2;
  }

  function intent(element: HTMLDivElement, next: number) {
    direction.current = next;
    if (next < 0) {
      following.current = false;
    } else if (next > 0 && atBottom(element)) {
      following.current = true;
    }
  }

  // 在捕获阶段处理内部手势，避免外层原生监听器先改变对话的滚动意图。
  function contain(event: UIEvent<HTMLDivElement>) {
    if (event.currentTarget.scrollHeight <= event.currentTarget.clientHeight) {
      return false;
    }
    event.stopPropagation();
    return true;
  }

  useLayoutEffect(() => {
    if (streaming && !wasStreaming.current) {
      following.current = true;
      direction.current = 0;
    }
    wasStreaming.current = streaming;
    const element = viewport.current;
    if (!element || !open) {
      return;
    }
    let restored = false;
    const update = () => {
      if (!element.clientHeight) {
        return;
      }
      element.toggleAttribute("data-scrollable", element.scrollHeight > element.clientHeight);
      if (streaming && following.current) {
        element.scrollTop = element.scrollHeight;
      } else if (!restored) {
        element.scrollTop = lastTop.current;
      }
      lastTop.current = element.scrollTop;
      restored = true;
    };
    const release = () => {
      if (dragging.current && atBottom(element)) {
        following.current = true;
        direction.current = 0;
      }
      dragging.current = false;
    };
    const observer = new ResizeObserver(update);
    observer.observe(element);
    if (element.firstElementChild) {
      observer.observe(element.firstElementChild);
    }
    window.addEventListener("pointerup", release);
    window.addEventListener("pointercancel", release);
    update();
    return () => {
      observer.disconnect();
      dragging.current = false;
      window.removeEventListener("pointerup", release);
      window.removeEventListener("pointercancel", release);
    };
  }, [open, streaming]);

  return {
    ref: viewport,
    onScroll(event: UIEvent<HTMLDivElement>) {
      const element = event.currentTarget;
      if (!open || !element.clientHeight || event.target !== element) {
        return;
      }
      const next = Math.sign(element.scrollTop - lastTop.current);
      if (dragging.current && next) {
        intent(element, next);
      }
      if (direction.current > 0 && next >= 0 && atBottom(element)) {
        following.current = true;
        direction.current = 0;
      }
      lastTop.current = element.scrollTop;
    },
    onWheelCapture(event: WheelEvent<HTMLDivElement>) {
      if (contain(event) && event.deltaY) {
        intent(event.currentTarget, Math.sign(event.deltaY));
      }
    },
    onTouchStartCapture(event: TouchEvent<HTMLDivElement>) {
      if (contain(event)) {
        touchY.current = event.touches[0]?.clientY ?? 0;
      }
    },
    onTouchMoveCapture(event: TouchEvent<HTMLDivElement>) {
      if (!contain(event)) {
        return;
      }
      const next = event.touches[0]?.clientY ?? touchY.current;
      if (next !== touchY.current) {
        intent(event.currentTarget, Math.sign(touchY.current - next));
      }
      touchY.current = next;
    },
    onPointerDownCapture(event: PointerEvent<HTMLDivElement>) {
      const element = event.currentTarget;
      const scrollbar = event.pointerType === "mouse" && element.scrollHeight > element.clientHeight
        && event.clientX >= element.getBoundingClientRect().left + element.clientLeft + element.clientWidth;
      if (scrollbar) {
        event.stopPropagation();
        dragging.current = true;
        following.current = false;
        direction.current = 0;
      }
    },
    onKeyDownCapture(event: KeyboardEvent<HTMLDivElement>) {
      if (event.target instanceof HTMLElement && event.target.closest("button, a, input, textarea, select, [contenteditable='true']")) {
        return;
      }
      if (!["ArrowUp", "ArrowDown", "PageUp", "PageDown", "Home", "End", " "].includes(event.key) || !contain(event)) {
        return;
      }
      intent(event.currentTarget, ["ArrowUp", "PageUp", "Home"].includes(event.key) || event.key === " " && event.shiftKey ? -1 : 1);
    },
  };
}
