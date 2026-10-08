"use client";

import {Button} from "@/components/ui/button";

import {
  Children,
  cloneElement,
  type ComponentProps,
  createContext,
  isValidElement,
  type ReactElement,
  type ReactNode,
  useContext,
  useId
} from "react";
import {createPortal} from "react-dom";

export const DialogFooterContext = createContext<HTMLElement | null | undefined>(undefined);
export const DialogFormContext = createContext<string | null>(null);
export type DialogCloseHandler = (afterClose?: () => void) => void;
export const DialogCloseContext = createContext<DialogCloseHandler | null>(null);

/** 操作完成后先退场，再更新列表、切换页面或移除弹窗。 */
export function DialogAction({onAction, ...props}: Omit<ComponentProps<"button">, "onClick"> & {
  onAction: (close: DialogCloseHandler) => void
}) {
  const close = useContext(DialogCloseContext);
  return <Button type="button" {...props} onClick={() => onAction(close ?? ((afterClose) => afterClose?.()))}/>;
}

/** 操作区移到弹窗底部后，用表单编号保留浏览器原生校验和回车提交。 */
export function DialogForm({id, children, ...props}: ComponentProps<"form">) {
  const generatedId = useId();
  const formId = id ?? generatedId;
  return <DialogFormContext.Provider value={formId}>
    <form {...props} id={formId}>{children}</form>
  </DialogFormContext.Provider>;
}

function connectButtons(children: ReactNode, formId: string | null): ReactNode {
  if (!formId) {
    return children;
  }
  return Children.map(children, (child) => {
    if (!isValidElement(child)) {
      return child;
    }
    const element = child as ReactElement<{ children?: ReactNode; form?: string; type?: string }>;
    if (element.type === "button" || element.type === Button) {
      return cloneElement(element, {form: element.props.form ?? formId});
    }
    return element.props.children ? cloneElement(element, {children: connectButtons(element.props.children, formId)}) : element;
  });
}

/** 由 React 在弹窗的固定操作区渲染，独立使用表单时仍留在表单内部。 */
export function DialogActions({children, className = "", ...props}: ComponentProps<"div">) {
  const footer = useContext(DialogFooterContext);
  const formId = useContext(DialogFormContext);
  const content = <div {...props}
                       className={`dialog-actions-content ${className}`}>{connectButtons(children, formId)}</div>;
  return footer === undefined ? content : footer ? createPortal(content, footer) : null;
}
