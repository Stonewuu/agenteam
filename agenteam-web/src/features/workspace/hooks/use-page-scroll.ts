"use client";

import {useLayoutEffect, useRef} from "react";

/** 正文单独保存滚动位置，页面标题由公共组件随滚动收缩。 */
export function usePageScroll(pathname: string, enabled: boolean) {
  const container = useRef<HTMLElement>(null);
  const positions = useRef(new Map<string, number>());

  useLayoutEffect(() => {
    const element = container.current;
    if (!element) {
      return;
    }
    const savedPositions = positions.current;
    const destination = enabled ? savedPositions.get(pathname) ?? 0 : 0;
    let lastPosition = destination;
    let restoring = destination > 0;
    let frame = 0;
    const notify = () => element.dispatchEvent(new Event("page-scroll-restored"));
    const remember = () => {
      if (restoring) {
        return;
      }
      lastPosition = element.scrollTop;
      savedPositions.set(pathname, lastPosition);
    };
    const restore = () => {
      frame = 0;
      if (!restoring) {
        return;
      }
      const available = Math.max(0, element.scrollHeight - element.clientHeight);
      element.scrollTop = Math.min(destination, available);
      if (available >= destination && Math.abs(element.scrollTop - destination) < 1) {
        restoring = false;
        lastPosition = destination;
      }
      notify();
    };
    const scheduleRestore = () => {
      if (restoring && !frame) {
        frame = requestAnimationFrame(restore);
      }
    };
    const takeOver = () => {
      restoring = false;
      cancelAnimationFrame(frame);
      frame = 0;
      remember();
    };
    if (!restoring) {
      element.scrollTop = 0;
      notify();
    } else {
      scheduleRestore();
    }
    // 查询结果出现前页面可能不够高，内容加载后再恢复；用户操作会取消等待。
    const resize = new ResizeObserver(scheduleRestore);
    if (element.firstElementChild) {
      resize.observe(element.firstElementChild);
    }
    element.addEventListener("scroll", remember, {passive: true});
    element.addEventListener("wheel", takeOver, {passive: true});
    element.addEventListener("touchstart", takeOver, {passive: true});
    element.addEventListener("pointerdown", takeOver, {passive: true});
    element.addEventListener("keydown", takeOver);
    return () => {
      savedPositions.set(pathname, lastPosition);
      if (savedPositions.size > 80) {
        savedPositions.delete(savedPositions.keys().next().value!);
      }
      cancelAnimationFrame(frame);
      resize.disconnect();
      element.removeEventListener("scroll", remember);
      element.removeEventListener("wheel", takeOver);
      element.removeEventListener("touchstart", takeOver);
      element.removeEventListener("pointerdown", takeOver);
      element.removeEventListener("keydown", takeOver);
    };
  }, [enabled, pathname]);

  return {container};
}
