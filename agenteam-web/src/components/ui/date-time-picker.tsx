"use client";

import {type ReactElement, type ReactNode, type RefObject, useRef, useSyncExternalStore} from "react";
import {Popover, PopoverContent, PopoverTrigger} from "./shadcn/popover";
import {Dialog, DialogContent, DialogTitle, DialogTrigger} from "./shadcn/dialog";
import styles from "./date-time-input.module.css";

const query = "(max-width: 600px)";
const readNarrow = () => window.matchMedia(query).matches;
const readServerNarrow = () => false;
function subscribeNarrow(listener: () => void) {
  const media = window.matchMedia(query);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}

/** 窄屏使用模态弹窗，避免日历与操作按钮受输入框下方的剩余高度限制。 */
export function DateTimePicker({open, onOpenChange, title, trigger, finalFocus, children, wide, focusCalendar = true}: {
  open: boolean; onOpenChange: (open: boolean) => void; title: string; trigger: ReactElement;
  finalFocus: RefObject<HTMLElement | null>; children: ReactNode | ((narrow: boolean) => ReactNode);
  wide?: boolean; focusCalendar?: boolean;
}) {
  const narrow = useSyncExternalStore(subscribeNarrow, readNarrow, readServerNarrow);
  const surface = useRef<HTMLDivElement>(null);
  const initialFocus = () => surface.current?.querySelector<HTMLElement>("[data-selected] button:not(:disabled)")
    ?? surface.current?.querySelector<HTMLElement>("[data-today] button:not(:disabled)") ?? true;
  const content = typeof children === "function" ? children(narrow) : children;
  if (narrow) {
    return <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogTrigger render={trigger}/>
      <DialogContent ref={surface} className={`${styles.popup} ${styles.mobilePopup}`} finalFocus={finalFocus} initialFocus={initialFocus}>
        <DialogTitle className={styles.mobileTitle}>{title}</DialogTitle>
        {content}
      </DialogContent>
    </Dialog>;
  }
  return <Popover open={open} onOpenChange={(next, details) => {
    if (!next && details.reason === "outside-press" && details.event.target === finalFocus.current) {
      details.cancel();
      return;
    }
    onOpenChange(next);
  }}>
    <PopoverTrigger render={trigger}/>
    <PopoverContent ref={surface} align={wide ? "start" : "end"} sideOffset={8}
      collisionAvoidance={wide ? {side: "shift", align: "shift"} : undefined}
      className={`agenteam-popup ${styles.popup} ${wide ? styles.widePopup : ""}`} aria-label={title}
      finalFocus={finalFocus} initialFocus={focusCalendar ? initialFocus : false}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.stopPropagation();
        }
      }}>{content}</PopoverContent>
  </Popover>;
}
