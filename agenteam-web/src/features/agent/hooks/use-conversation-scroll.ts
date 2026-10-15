"use client";

import {useCallback, useLayoutEffect, useRef, useState} from "react";
import type {ConversationMessage} from "../types/execution";
import {conversationReadingPosition, hasNewConversationContent, rememberConversationPosition, type ConversationReadingPosition} from "../lib/conversation-reading";
import type {LiveRunStep} from "../lib/conversation-steps";

const emptyMessages: ConversationMessage[] = [];

/** 保留阅读位置，只有正在底部阅读时才跟随；返回操作随最新内容平滑移动。 */
export function useConversationScroll(scope: string, incoming?: readonly ConversationMessage[], steps?: ReadonlyMap<string, LiveRunStep>) {
  const messages = incoming ?? emptyMessages;
  const conversationRef = useRef<HTMLDivElement>(null);
  const shouldStickToBottomRef = useRef(true);
  const previous = useRef<{
    scope: string;
    messages: readonly ConversationMessage[];
    steps?: ReadonlyMap<string, LiveRunStep>
  }>({scope, messages: []});
  const unseen = useRef(false);
  const motion = useRef(0);
  const publishFrame = useRef(0);
  const scrollIntent = useRef(0);
  const lastTop = useRef(0);
  const gestureRevision = useRef(0);
  const [view, setView] = useState({scope, away: false, unseen: false});
  const restoring = useRef<{scope: string; position: ConversationReadingPosition | undefined}>({scope, position: conversationReadingPosition(scope)});

  const restorePosition = useCallback(() => {
    const viewport = conversationRef.current;
    const saved = restoring.current.scope === scope ? restoring.current.position : undefined;
    if (!viewport || !saved || saved.atBottom) {
      return;
    }
    const anchor = [...viewport.querySelectorAll<HTMLElement>("[data-conversation-message]")]
      .find(element => element.dataset.conversationMessage === saved.anchorId);
    if (anchor) {
      viewport.scrollTop += anchor.getBoundingClientRect().top - viewport.getBoundingClientRect().top - saved.offset;
      lastTop.current = viewport.scrollTop;
    }
  }, [scope]);

  const rememberPosition = useCallback(() => {
    const viewport = conversationRef.current;
    if (!viewport || previous.current.scope !== scope || !previous.current.messages.length) {
      return;
    }
    const top = viewport.getBoundingClientRect().top;
    const elements = [...viewport.querySelectorAll<HTMLElement>("[data-conversation-message]")];
    const restoringPosition = restoring.current.scope === scope ? restoring.current.position : undefined;
    if (restoringPosition && !restoringPosition.atBottom && !elements.some(element => element.dataset.conversationMessage === restoringPosition.anchorId)) {
      return;
    }
    const anchor = elements
      .find(element => element.getBoundingClientRect().bottom > top);
    if (anchor) {
      rememberConversationPosition(scope, {top: viewport.scrollTop, anchorId: anchor.dataset.conversationMessage ?? null,
        offset: anchor.getBoundingClientRect().top - top, atBottom: shouldStickToBottomRef.current});
    }
  }, [scope]);

  const publish = useCallback(() => {
    const viewport = conversationRef.current;
    if (!viewport) {
      return;
    }
    const away = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight > 2;
    if (shouldStickToBottomRef.current) {
      unseen.current = false;
    }
    const hasNew = unseen.current;
    setView((value) => value.scope === scope && value.away === away && value.unseen === hasNew ? value : {
      scope,
      away,
      unseen: hasNew
    });
  }, [scope]);
  const queuePublish = useCallback(() => {
    cancelAnimationFrame(publishFrame.current);
    publishFrame.current = requestAnimationFrame(publish);
  }, [publish]);
  const cancelMotion = useCallback(() => {
    cancelAnimationFrame(motion.current);
    motion.current = 0;
  }, []);
  const writeScroll = useCallback((top: number) => {
    const viewport = conversationRef.current;
    if (viewport) {
      viewport.scrollTop = top;
      lastTop.current = viewport.scrollTop;
    }
  }, []);

  useLayoutEffect(() => {
    const viewport = conversationRef.current;
    if (!viewport) {
      return;
    }
    let draggingScrollbar = false;
    let touchY = 0;
    const atBottom = () => viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight <= 2;
    const interrupt = () => {
      cancelMotion();
      shouldStickToBottomRef.current = false;
    };
    const intent = (direction: number) => {
      restoring.current.position = undefined;
      gestureRevision.current++;
      scrollIntent.current = direction;
      if (direction < 0 || motion.current) {
        interrupt();
      }
      if (direction > 0 && atBottom()) {
        shouldStickToBottomRef.current = true;
      }
      queuePublish();
    };
    const track = () => {
      const direction = Math.sign(viewport.scrollTop - lastTop.current);
      if (draggingScrollbar && direction) {
        intent(direction);
      }
      // 布局变化也会触发 scroll；只有明确向下阅读并到达底部才恢复跟随。
      if (!motion.current && scrollIntent.current > 0 && direction >= 0 && atBottom()) {
        shouldStickToBottomRef.current = true;
        scrollIntent.current = 0;
      }
      lastTop.current = viewport.scrollTop;
      rememberPosition();
      queuePublish();
    };
    const wheel = (event: WheelEvent) => {
      if (event.deltaY) {
        intent(Math.sign(event.deltaY));
      }
    };
    const key = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select, button, summary, [contenteditable='true']")) {
        return;
      }
      if (["ArrowUp", "PageUp", "Home"].includes(event.key) || event.key === " " && event.shiftKey) {
        intent(-1);
      } else if (["ArrowDown", "PageDown", "End", " "].includes(event.key)) {
        intent(1);
      }
    };
    const pointer = (event: PointerEvent) => {
      if (motion.current) {
        interrupt();
        scrollIntent.current = 0;
      }
      draggingScrollbar = event.pointerType === "mouse" && event.clientX >= viewport.getBoundingClientRect().left + viewport.clientLeft + viewport.clientWidth;
      if (draggingScrollbar) {
        interrupt();
        lastTop.current = viewport.scrollTop;
      }
    };
    const releasePointer = () => {
      draggingScrollbar = false;
    };
    const touchStart = (event: TouchEvent) => {
      touchY = event.touches[0]?.clientY ?? 0;
    };
    const touchMove = (event: TouchEvent) => {
      const next = event.touches[0]?.clientY ?? touchY;
      if (next !== touchY) {
        intent(Math.sign(touchY - next));
      }
      touchY = next;
    };
    const resize = () => {
      restorePosition();
      if (shouldStickToBottomRef.current && !motion.current) {
        writeScroll(viewport.scrollHeight);
      }
      queuePublish();
      rememberPosition();
    };
    viewport.addEventListener("scroll", track, {passive: true});
    viewport.addEventListener("wheel", wheel, {passive: true});
    viewport.addEventListener("touchstart", touchStart, {passive: true});
    viewport.addEventListener("touchmove", touchMove, {passive: true});
    viewport.addEventListener("keydown", key);
    viewport.addEventListener("pointerdown", pointer);
    window.addEventListener("pointerup", releasePointer);
    window.addEventListener("pointercancel", releasePointer);
    const observer = new ResizeObserver(resize);
    observer.observe(viewport, {box: "border-box"});
    if (viewport.firstElementChild) {
      observer.observe(viewport.firstElementChild, {box: "border-box"});
    }
    return () => {
      observer.disconnect();
      cancelMotion();
      cancelAnimationFrame(publishFrame.current);
      viewport.removeEventListener("scroll", track);
      viewport.removeEventListener("wheel", wheel);
      viewport.removeEventListener("touchstart", touchStart);
      viewport.removeEventListener("touchmove", touchMove);
      viewport.removeEventListener("keydown", key);
      viewport.removeEventListener("pointerdown", pointer);
      window.removeEventListener("pointerup", releasePointer);
      window.removeEventListener("pointercancel", releasePointer);
    };
  }, [scope, queuePublish, cancelMotion, writeScroll, restorePosition, rememberPosition]);

  useLayoutEffect(() => {
    if (previous.current.scope !== scope) {
      cancelMotion();
      const saved = conversationReadingPosition(scope);
      restoring.current = {scope, position: saved};
      shouldStickToBottomRef.current = saved?.atBottom ?? true;
      unseen.current = false;
      scrollIntent.current = 0;
    } else if (!shouldStickToBottomRef.current && previous.current.messages.length && (hasNewConversationContent(previous.current.messages, messages)
      || steps && [...steps.values()].some((value) => value.sequence !== previous.current.steps?.get(value.step.id)?.sequence))) {
      unseen.current = true;
    }
    previous.current = {scope, messages, steps};
    if (restoring.current.scope === scope && restoring.current.position && !restoring.current.position.atBottom) {
      shouldStickToBottomRef.current = false;
      restorePosition();
    }
    if (conversationRef.current && shouldStickToBottomRef.current && !motion.current) {
      writeScroll(conversationRef.current.scrollHeight);
    }
    queuePublish();
    if (!restoring.current.position || messages.some(message => message.id === restoring.current.position?.anchorId)) {
      rememberPosition();
    }
  }, [scope, messages, steps, queuePublish, cancelMotion, writeScroll, restorePosition, rememberPosition]);

  const scrollToBottom = useCallback(() => {
    const viewport = conversationRef.current;
    if (!viewport) {
      return;
    }
    cancelMotion();
    restoring.current.position = undefined;
    unseen.current = false;
    scrollIntent.current = 0;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      shouldStickToBottomRef.current = true;
      writeScroll(viewport.scrollHeight);
      publish();
      return;
    }
    shouldStickToBottomRef.current = false;
    const origin = viewport.scrollTop;
    const started = performance.now();
    const advance = (now: number) => {
      const progress = Math.min(1, (now - started) / 320);
      const destination = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
      writeScroll(origin + (destination - origin) * (1 - (1 - progress) ** 3));
      if (progress < 1) {
        motion.current = requestAnimationFrame(advance);
      } else {
        motion.current = 0;
        shouldStickToBottomRef.current = true;
        publish();
      }
    };
    motion.current = requestAnimationFrame(advance);
    queuePublish();
  }, [cancelMotion, publish, queuePublish, writeScroll]);

  const preserveHistoryPosition = useCallback(async (load: () => Promise<void>) => {
    const viewport = conversationRef.current;
    const top = viewport?.scrollTop ?? 0;
    const height = viewport?.scrollHeight ?? 0;
    const anchor = viewport && [...viewport.querySelectorAll<HTMLElement>("[data-conversation-message]")]
      .find((element) => element.getBoundingClientRect().bottom > viewport.getBoundingClientRect().top);
    const anchorTop = anchor?.getBoundingClientRect().top ?? 0;
    const gesture = gestureRevision.current;
    cancelMotion();
    shouldStickToBottomRef.current = false;
    scrollIntent.current = 0;
    await load();
    requestAnimationFrame(() => {
      if (viewport && viewport === conversationRef.current && previous.current.scope === scope && gestureRevision.current === gesture) {
        // 锚定原消息，避免把加载期间新增的流式正文高度也算作历史消息高度。
        writeScroll(anchor?.isConnected ? viewport.scrollTop + anchor.getBoundingClientRect().top - anchorTop : top + viewport.scrollHeight - height);
        queuePublish();
      }
    });
  }, [scope, cancelMotion, writeScroll, queuePublish]);

  return {
    conversationRef,
    shouldStickToBottomRef,
    scrollToBottom,
    preserveHistoryPosition,
    awayFromBottom: view.scope === scope && view.away,
    hasUnseenContent: view.scope === scope && view.unseen
  };
}
