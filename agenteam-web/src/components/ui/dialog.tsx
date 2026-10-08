"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ComponentProps, type ReactNode, type Ref, useCallback, useImperativeHandle, useRef, useState} from "react";
import {Dialog as DialogRoot, DialogClose, DialogContent, DialogTitle} from "./shadcn/dialog";
import {IconX} from "./icons";
import {DialogCloseContext, type DialogCloseHandler, DialogFooterContext, DialogFormContext} from "./dialog-actions";
import {useOverlayTransition} from "./use-overlay-transition";
import {LoadingSurface, useLoadingSurface} from "./loading-transition";

export {DialogAction, DialogActions, DialogForm} from "./dialog-actions";

export type DialogHandle = { close: DialogCloseHandler };

/** 表单提交或放弃修改时，通过同一个退场流程完成关闭。 */
export function useDialogControl() {
  const ref = useRef<DialogHandle>(null);
  const close = useCallback<DialogCloseHandler>((afterClose) => {
    if (ref.current) {
      ref.current.close(afterClose);
    } else {
      afterClose?.();
    }
  }, []);
  return {ref, close};
}

export function Dialog({
                         title,
                         icon,
                         children,
                         onClose,
                         onRequestClose,
                         busy = false,
                         wide = false,
                         drawer = false,
                         size,
                         footer,
                         bodyClassName = "",
                         className = "",
                         headerDetails,
                         tall = false,
                         dialogRef,
                         variant
                       }: {
  title: string;
  icon?: ReactNode;
  children: ReactNode;
  onClose: () => void;
  onRequestClose?: () => boolean;
  busy?: boolean;
  wide?: boolean;
  drawer?: boolean;
  size?: "small" | "medium" | "large";
  footer?: ReactNode;
  bodyClassName?: string;
  tall?: boolean;
  dialogRef?: Ref<DialogHandle>;
  className?: string;
  headerDetails?: ReactNode;
  variant?: "discard";
}) {
  const uiText = useT();
  const {open, close, onOpenChangeComplete} = useOverlayTransition(onClose);
  useImperativeHandle(dialogRef, () => ({close}), [close]);
  const body = useRef<HTMLDivElement>(null);
  const loadingSurface = useLoadingSurface();
  const [footerElement, setFooterElement] = useState<HTMLElement | null>(null);
  const previous = useRef(typeof document === "undefined" ? null : document.activeElement as HTMLElement | null);
  return <DialogRoot open={open} onOpenChange={(value) => {
    if (!value && !busy && (!onRequestClose || onRequestClose())) {
      close();
    }
  }} onOpenChangeComplete={onOpenChangeComplete}>
    <DialogContent ref={loadingSurface} showCloseButton={false} role={variant === "discard" ? "alertdialog" : "dialog"}
                   className={`agenteam-dialog ${drawer ? "drawer" : "modal"} ${variant === "discard" ? "small confirmation" : size ?? (wide ? "large" : "medium")}${tall ? " tall" : ""} ${className}`}
                   initialFocus={() => (variant === "discard" ? body.current?.parentElement?.querySelector<HTMLElement>(".modal-footer button") : body.current?.querySelector<HTMLElement>('input:not([type="hidden"]):not([aria-hidden="true"]):not(:disabled),textarea:not(:disabled),button[role="combobox"]:not(:disabled)')) ?? true}
                   finalFocus={() => previous.current?.isConnected ? previous.current : true} inert={!open}
                   onSubmit={(event) => event.stopPropagation()}>
      <header className="modal-header">
        <div className="modal-heading"><div className="modal-title-line">
          {icon && <span className="modal-title-icon">{icon}</span>}<DialogTitle>{title}</DialogTitle>
        </div>{headerDetails}</div>
        <DialogClose className="icon-button" aria-label={uiText("关闭")} disabled={busy}><IconX
          size={20}/></DialogClose>
      </header>
      <DialogCloseContext.Provider value={close}><DialogFormContext.Provider value={null}><DialogFooterContext.Provider
        value={footerElement}>
        <div ref={body} className={`modal-body ${bodyClassName}`}><LoadingSurface>{variant === "discard" &&
          <p className="confirmation-description">{uiText("本次未保存的修改将丢失。")}</p>}{children}</LoadingSurface>
        </div>
        <footer ref={setFooterElement} className="modal-footer">{footer}</footer>
      </DialogFooterContext.Provider></DialogFormContext.Provider></DialogCloseContext.Provider>
    </DialogContent>
  </DialogRoot>;
}

/** 普通取消也通过弹窗的关闭流程，保留确认检查、退场和焦点恢复。 */
export function DialogCancel(props: ComponentProps<typeof DialogClose>) {
  return <DialogClose {...props} />;
}
