"use client";

import {useCallback, useLayoutEffect, useRef} from "react";

/** 单独测量文字高度，避免把可见输入框先缩小再撑开而中断动画。 */
export function useComposerInputSize(text: string) {
  const input = useRef<HTMLTextAreaElement>(null);
  const measurement = useRef<HTMLTextAreaElement | null>(null);
  const measure = useCallback(() => {
    const element = input.current;
    const mirror = measurement.current;
    if (!element || !mirror) {
      return;
    }
    const style = getComputedStyle(element);
    Object.assign(mirror.style, {
      width: `${element.getBoundingClientRect().width}px`, font: style.font,
      letterSpacing: style.letterSpacing, padding: style.padding, border: style.border,
      boxSizing: style.boxSizing, scrollbarGutter: style.scrollbarGutter,
      wordBreak: style.wordBreak, overflowWrap: style.overflowWrap, tabSize: style.tabSize,
    });
    mirror.value = element.value;
    const border = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth);
    const height = Math.min(parseFloat(style.maxHeight), Math.max(parseFloat(style.minHeight), mirror.scrollHeight + border));
    element.style.height = `${height}px`;
  }, []);

  useLayoutEffect(() => {
    const element = input.current;
    if (!element) {
      return;
    }
    const mirror = document.createElement("textarea");
    mirror.tabIndex = -1;
    mirror.setAttribute("aria-hidden", "true");
    mirror.style.cssText = "position:fixed;left:-10000px;top:0;height:0;min-height:0;max-height:none;visibility:hidden;pointer-events:none;overflow-y:auto;resize:none;";
    document.body.append(mirror);
    measurement.current = mirror;
    let previousWidth = -1;
    const observer = new ResizeObserver(() => {
      const width = element.getBoundingClientRect().width;
      if (width !== previousWidth) {
        previousWidth = width;
        measure();
      }
    });
    observer.observe(element);
    measure();
    return () => {
      observer.disconnect();
      mirror.remove();
      measurement.current = null;
    };
  }, [measure]);
  useLayoutEffect(measure, [text, measure]);
  return input;
}
