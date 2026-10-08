"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {type RefObject, useEffect, useLayoutEffect, useRef, useState} from "react";
import {fieldErrorMessages, validationMessage} from "@/lib/form-errors";
import {IconAlertCircle} from "./icons";
import {Collapsible, CollapsibleContent} from "./shadcn/collapsible";
import styles from "./surface.module.css";

export function ErrorFeedback({error, fieldErrors = {}, requestId, onFieldChange}: {
  error: string; fieldErrors?: Record<string, string[]>; requestId?: string; onFieldChange?: (field: string) => void;
}) {
  const uiText = useT();
  const container = useRef<HTMLSpanElement>(null);
  const editing = useRef(false);
  const fieldMessages = fieldErrorMessages(fieldErrors, (message) => localizeUiMessage(message, uiText));
  const messages = fieldMessages.length ? fieldMessages : error.trim() ? [validationMessage(error)] : [];
  const feedbackKey = messages.join("\n");
  useLayoutEffect(() => {
    if (!feedbackKey) {
      return;
    }
    const feedback = container.current;
    const form = feedback?.closest("form");
    const entries = Object.entries(fieldErrors).filter(([, values]) => values.some((value) => value.trim()));
    const allInline = Boolean(form) && entries.length > 0 && entries.every(([field, values]) => {
      const input = form!.querySelector<HTMLElement>(`[name="${CSS.escape(field)}"][aria-invalid="true"]`);
      const described = input?.getAttribute("aria-describedby")?.split(/\s+/).map((id) => document.getElementById(id)?.textContent ?? "").join(" ") ?? "";
      return Boolean(input?.getClientRects().length) && values.every((value) => described.includes(localizeUiMessage(validationMessage(value), uiText)));
    });
    feedback?.toggleAttribute("data-inline-errors", allInline);
  }, [fieldErrors, feedbackKey, uiText]);
  useEffect(() => {
    const form = container.current?.closest("form");
    if (!form || !onFieldChange) {
      return;
    }
    const changed = (event: Event) => {
      const field = (event.target as HTMLElement).closest("[name]")?.getAttribute("name");
      if (field && fieldErrors[field]) {
        editing.current = true;
        onFieldChange(field);
      }
    };
    form.addEventListener("input", changed);
    form.addEventListener("change", changed);
    return () => {
      form.removeEventListener("input", changed);
      form.removeEventListener("change", changed);
    };
  }, [fieldErrors, onFieldChange]);
  useEffect(() => {
    // 编辑时收起旧提示，不把焦点从正在输入的字段移到其他错误字段。
    if (editing.current) {
      editing.current = false;
      return;
    }
    if (!feedbackKey) {
      return;
    }
    const frame = requestAnimationFrame(() => {
      const feedback = container.current;
      const form = feedback?.closest("form");
      const invalid = Array.from(form?.querySelectorAll<HTMLElement>('[aria-invalid="true"]') ?? [])
        .find((element) => element.matches('input:not([type="hidden"]), textarea, select, button, [tabindex]')
          && !element.matches(':disabled, [aria-hidden="true"]') && element.getClientRects().length > 0);
      const target = invalid ?? feedback;
      target?.focus({preventScroll: true});
      target?.scrollIntoView({
        block: "nearest",
        behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth"
      });
    });
    return () => cancelAnimationFrame(frame);
  }, [feedbackKey, requestId]);
  return <FeedbackContent messages={messages} containerRef={container}/>;
}

export function FieldErrorFeedback({messages = [], id}: { messages?: string[]; id?: string }) {
  return <FeedbackContent messages={[...new Set(messages.map(validationMessage).filter(Boolean))]} id={id} field/>;
}

function FeedbackContent({messages, containerRef, id, field = false}: {
  messages: string[]; containerRef?: RefObject<HTMLSpanElement | null>; id?: string; field?: boolean;
}) {
  const uiText = useT();
  const key = messages.join("\n");
  const open = messages.length > 0;
  const [content, setContent] = useState({key, messages});
  // 收起过程中保留最后一次提示，让高度和透明度一起完成过渡。
  if (open && key !== content.key) {
    setContent({key, messages});
  }
  return <Collapsible open={open} render={<span className={styles.feedbackPresence}/>}>
    <CollapsibleContent render={<span/>} className={`agenteam-disclosure-content ${styles.feedbackTransition}`}
                        inert={!open}>
      <span ref={containerRef} id={id} className={field ? styles.fieldFeedback : styles.errorFeedback}
            role={open ? "alert" : undefined} tabIndex={field || !open ? undefined : -1}
            data-form-error={field ? undefined : "true"}>
        <IconAlertCircle size={field ? 14 : 16} className={styles.feedbackIcon}/>
        {content.messages.length === 1 ? <span>{localizeUiMessage(content.messages[0], uiText)}</span> :
          <span className={styles.feedbackMessages} role="list" aria-label={uiText("需要修改的内容")}>
          {content.messages.map((message) => <span role="listitem"
                                                   key={message}>{localizeUiMessage(message, uiText)}</span>)}
        </span>}
      </span>
    </CollapsibleContent>
  </Collapsible>;
}
