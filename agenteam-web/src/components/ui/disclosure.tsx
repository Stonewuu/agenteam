"use client";

import {Children, type ComponentProps, isValidElement, type ReactNode, useLayoutEffect, useRef, useState} from "react";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "./shadcn/collapsible";
import {IconChevronRight} from "./icons";

export function Disclosure({
                             open: initialOpen = false,
                             keepMounted = true,
                             animateContentHeight = false,
                             children,
                             ...props
                           }: Omit<ComponentProps<typeof Collapsible>, "open" | "onOpenChange"> & {
  open?: boolean; keepMounted?: boolean; animateContentHeight?: boolean; children: ReactNode;
}) {
  const [state, setState] = useState({initialOpen, open: initialOpen});
  if (state.initialOpen !== initialOpen) {
    setState({initialOpen, open: initialOpen});
  }
  const items = Children.toArray(children);
  const isSummary = (child: ReactNode) => isValidElement(child) && child.type === DisclosureSummary;
  const content = items.filter((child) => !isSummary(child));
  return <Collapsible {...props} open={state.open} onOpenChange={(open) => setState({initialOpen, open})}>
    {items.filter(isSummary)}
    <CollapsibleContent className="agenteam-disclosure-content" data-animate-height={animateContentHeight || undefined}
                        keepMounted={keepMounted} inert={!state.open}>
      {animateContentHeight ? <MeasuredDisclosureContent>{content}</MeasuredDisclosureContent> : content}
    </CollapsibleContent>
  </Collapsible>;
}

/** 直接更新折叠面板的目标高度，异步内容到达时从当前高度继续过渡。 */
export function MeasuredDisclosureContent({children}: { children: ReactNode }) {
  const element = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const content = element.current;
    const panel = content?.parentElement;
    if (!content || !panel) {
      return;
    }
    let measuredHeight = -1;
    const measure = (height: number) => {
      if (Math.abs(height - measuredHeight) < 0.5) {
        return;
      }
      measuredHeight = height;
      panel.style.setProperty("--disclosure-content-height", `${height}px`);
    };
    measure(content.getBoundingClientRect().height);
    const observer = new ResizeObserver(([entry]) => {
      measure(entry.borderBoxSize[0]?.blockSize ?? content.getBoundingClientRect().height);
    });
    const movingChildren = new Set<EventTarget>();
    const followChild = (event: TransitionEvent) => {
      if (event.propertyName !== "height" || !event.target) {
        return;
      }
      if (event.type === "transitionrun") {
        movingChildren.add(event.target);
      } else {
        movingChildren.delete(event.target);
      }
      // 内层已在展开时，外层直接跟随每一帧尺寸，避免两层高度动画互相追赶。
      panel.toggleAttribute("data-following-child-height", movingChildren.size > 0);
    };
    observer.observe(content);
    content.addEventListener("transitionrun", followChild);
    content.addEventListener("transitionend", followChild);
    content.addEventListener("transitioncancel", followChild);
    return () => {
      observer.disconnect();
      content.removeEventListener("transitionrun", followChild);
      content.removeEventListener("transitionend", followChild);
      content.removeEventListener("transitioncancel", followChild);
      panel.removeAttribute("data-following-child-height");
      panel.style.removeProperty("--disclosure-content-height");
    };
  }, []);
  return <div ref={element} className="agenteam-disclosure-measure">{children}</div>;
}

export function DisclosureSummary({children, className = "", ...props}: ComponentProps<typeof CollapsibleTrigger>) {
  return <CollapsibleTrigger {...props} className={`agenteam-disclosure-trigger ${className}`}><IconChevronRight
    size={13}/><span>{children}</span></CollapsibleTrigger>;
}
