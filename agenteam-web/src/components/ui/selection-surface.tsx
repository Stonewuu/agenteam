"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type KeyboardEvent, type ReactNode, type RefObject, useCallback, useEffect, useRef, useState} from "react";
import {Popover} from "@base-ui/react/popover";
import {Dialog} from "./dialog";
import {DialogCloseContext} from "./dialog-actions";
import {useOverlayTransition} from "./use-overlay-transition";
import {LoadingSurface, useLoadingSurface} from "./loading-transition";
import {IconX} from "./icons";

export {DialogAction as SelectionAction} from "./dialog-actions";

export type SelectionAnchor = HTMLElement | RefObject<HTMLElement | null> | {
  getBoundingClientRect: () => DOMRect;
  contextElement?: HTMLElement
};

function selectionSide(anchor?: SelectionAnchor | null): "top" | "bottom" {
  if (!anchor || typeof window === "undefined") {
    return "top";
  }
  const element = "current" in anchor ? anchor.current : anchor;
  if (!element) {
    return "top";
  }
  const rect = element.getBoundingClientRect();
  const viewport = window.visualViewport;
  const above = rect.top - (viewport?.offsetTop ?? 0);
  const below = (viewport?.height ?? window.innerHeight) + (viewport?.offsetTop ?? 0) - rect.bottom;
  return above >= Math.min(320, below) ? "top" : "bottom";
}

export function SelectionSurface({title, anchor, returnFocus, children, onClose}: {
  title: string;
  anchor?: SelectionAnchor | null;
  returnFocus?: RefObject<HTMLElement | null>;
  children: ReactNode;
  onClose: () => void;
}) {
  const uiText = useT();
  const {open, close, onOpenChangeComplete} = useOverlayTransition(onClose);
  // 打开时确定方向，候选返回后不因内容高度改变而跳到输入框另一侧。
  const [side] = useState(() => selectionSide(anchor));
  const popup = useRef<HTMLDivElement>(null);
  const loadingSurface = useLoadingSurface();
  const attachPopup = useCallback((element: HTMLDivElement | null) => {
    popup.current = element;
    const cleanup = loadingSurface(element);
    return () => {
      popup.current = null;
      cleanup?.();
    };
  }, [loadingSurface]);
  const commandInput = typeof HTMLTextAreaElement !== "undefined" && anchor && "contextElement" in anchor && anchor.contextElement instanceof HTMLTextAreaElement ? anchor.contextElement : null;
  useEffect(() => {
    if (!commandInput || !open) {
      return;
    }

    function commandKeys(event: globalThis.KeyboardEvent) {
      if (event.isComposing || event.keyCode === 229 || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) {
        return;
      }
      if (!["Escape", "ArrowDown", "ArrowUp", "Enter"].includes(event.key)) {
        return;
      }
      event.preventDefault();
      event.stopPropagation();
      if (event.key === "Escape") {
        close();
        return;
      }
      const options = Array.from(popup.current?.querySelectorAll<HTMLElement>("[data-selection-option]:not(:disabled):not([aria-disabled=true])") ?? []);
      const option = event.key === "ArrowUp" ? options.at(-1) : options[0];
      if (event.key === "Enter") {
        option?.click();
      } else {
        option?.focus({preventScroll: true});
        option?.scrollIntoView({block: "nearest"});
      }
    }

    commandInput.addEventListener("keydown", commandKeys);
    return () => commandInput.removeEventListener("keydown", commandKeys);
  }, [commandInput, open, close]);

  function keys(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return;
    }
    const target = event.target as HTMLElement;
    const options = [...(popup.current?.querySelectorAll<HTMLElement>("[data-selection-option]:not(:disabled):not([aria-disabled=true])") ?? [])];
    const index = options.indexOf(target);
    const searching = target instanceof HTMLInputElement && target.type !== "checkbox";
    if (!options.length || (!searching && index < 0)) {
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp" || (!searching && (event.key === "Home" || event.key === "End"))) {
      event.preventDefault();
      const next = event.key === "Home" ? 0 : event.key === "End" ? options.length - 1 : event.key === "ArrowDown" ? (index + 1) % options.length : (index < 0 ? options.length - 1 : (index - 1 + options.length) % options.length);
      options[next].focus();
      options[next].scrollIntoView({block: "nearest"});
    } else if (event.key === "Enter" && (searching || target instanceof HTMLInputElement && target.type === "checkbox")) {
      event.preventDefault();
      (searching ? options[0] : target).click();
    }
  }

  if (!anchor) {
    return <Dialog title={title} onClose={onClose}>
      <div className="selection-content">{children}</div>
    </Dialog>;
  }
  return <Popover.Root open={open} modal={false} onOpenChange={(value, details) => {
    if (!value && commandInput && details.event.target === commandInput && details.reason !== "escape-key") {
      details.cancel();
      return;
    }
    if (!value) {
      close();
    }
  }} onOpenChangeComplete={onOpenChangeComplete}>
    <Popover.Portal><Popover.Positioner anchor={anchor} side={side} align="start" sideOffset={9} collisionPadding={12}
                                        collisionAvoidance={{side: "shift", align: "shift"}} positionMethod="fixed"
                                        className="agenteam-positioner">
      <Popover.Popup ref={attachPopup} className="selection-popup agenteam-popup agenteam-glass" inert={!open}
                     onKeyDown={keys}
                     initialFocus={() => commandInput ? false : popup.current?.querySelector<HTMLInputElement>('input[type="search"],input[type="text"]') ?? true}
                     finalFocus={() => commandInput && document.activeElement !== document.body && !popup.current?.contains(document.activeElement) ? false : returnFocus?.current?.isConnected ? returnFocus.current : true}>
        <header><Popover.Title>{title}</Popover.Title><Popover.Close className="icon-button"
                                                                     aria-label={uiText("关闭选择")}><IconX size={17}/></Popover.Close>
        </header>
        <DialogCloseContext.Provider value={close}><LoadingSurface>
          <div className="selection-content">{children}</div>
        </LoadingSurface></DialogCloseContext.Provider>
      </Popover.Popup>
    </Popover.Positioner></Popover.Portal>
  </Popover.Root>;
}
