"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { DialogCloseHandler } from "./dialog-actions";

/** 保留退出中的内容，等组件库确认过渡结束后再执行关闭结果。 */
export function useOverlayTransition(onClose: () => void) {
  const [open, setOpen] = useState(false);
  const opened = useRef(false), closing = useRef(false), afterClose = useRef<(() => void) | null>(null);
  useEffect(() => {
    // 先挂载关闭状态，首次打开才能经过组件库的进场状态。
    const frame = requestAnimationFrame(() => {
 if (!closing.current) {
 opened.current = true; setOpen(true); 
} 
});
    return () => cancelAnimationFrame(frame);
  }, []);
  const close = useCallback<DialogCloseHandler>((complete = onClose) => {
    if (closing.current) {
return;
}
    closing.current = true;
    if (!opened.current) {
 complete(); return; 
}
    afterClose.current = complete;
    setOpen(false);
  }, [onClose]);
  const onOpenChangeComplete = useCallback((value: boolean) => {
    if (!value) {
 const complete = afterClose.current; afterClose.current = null; complete?.(); 
}
  }, []);
  return { open, close, onOpenChangeComplete };
}
