"use client";

import {type RefObject, useEffect, useRef, useState} from "react";
import {type ComposerSelectionContext, readComposerCommand} from "../lib/composer-selection";

export function useComposerSelection(input: RefObject<HTMLTextAreaElement | null>, onText: (value: string) => void, onDismiss?: () => void) {
  const [context, setContext] = useState<ComposerSelectionContext | null>(null);
  const command = useRef<ComposerSelectionContext["command"]>(undefined);
  useEffect(() => {
    const element = input.current;
    const token = context?.command;
    if (!element || !token) {
      return;
    }

    function check(event: Event) {
      if ((event as InputEvent).isComposing) {
        return;
      }
      // 等输入框自己的 onChange 更新受控文字后再关闭，避免退格被旧文字覆盖。
      queueMicrotask(() => {
        if (!element!.isConnected || command.current !== token) {
          return;
        }
        const current = readComposerCommand(element!.value, element!.selectionStart);
        if (!current || current.start !== token!.start || current.marker !== token!.text[0] || element!.selectionStart !== element!.selectionEnd) {
          command.current = undefined;
          setContext(null);
          onDismiss?.();
        }
      });
    }

    element.addEventListener("input", check);
    element.addEventListener("select", check);
    element.addEventListener("keyup", check);
    element.addEventListener("click", check);
    return () => {
      element.removeEventListener("input", check);
      element.removeEventListener("select", check);
      element.removeEventListener("keyup", check);
      element.removeEventListener("click", check);
    };
  }, [context, input, onDismiss]);

  function prepare(next?: ComposerSelectionContext) {
    command.current = next?.command;
    setContext(next ?? (input.current ? {anchor: input.current} : null));
  }

  function consume() {
    const token = command.current;
    command.current = undefined;
    const element = input.current;
    if (!token || !element || element.value.slice(token.start, token.end) !== token.text) {
      return;
    }
    setContext((current) => current ? {...current, command: undefined} : null);
    onText(element.value.slice(0, token.start) + element.value.slice(token.end));
    requestAnimationFrame(() => {
      if (element.isConnected) {
        element.setSelectionRange(token.start, token.start);
      }
    });
  }

  return {context, prepare, consume, focusTarget: input};
}
