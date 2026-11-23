"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {useLayoutEffect, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {IconAlertCircle, IconChevronDown, IconFolder} from "@/components/ui/icons";
import type {useConversationProject} from "../hooks/use-conversation-project";
import {ProjectPickerDialog} from "./project-picker-dialog";
import styles from "./project-picker.module.css";

export function ConversationProjectControl({state, showError = true, inlineReload = false}: {
  state: ReturnType<typeof useConversationProject>;
  showError?: boolean;
  inlineReload?: boolean;
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const measure = useRef<HTMLSpanElement>(null);
  const hasError = Boolean(state.error);
  const errorText = hasError ? localizeUiMessage(state.error ?? "", uiText) : "";
  const label = state.loading ? uiText("正在加载项目…") : state.selected?.name ?? (hasError ? uiText("项目暂不可用") : uiText("选择项目"));
  useLayoutEffect(() => {
    const content = measure.current;
    if (!content || !trigger.current) {
      return;
    }
    const resize = () => {
      trigger.current?.style.setProperty("--project-width", `${Math.ceil(content.getBoundingClientRect().width) + 55}px`);
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(content);
    return () => observer.disconnect();
  }, [state.visible]);
  if (!state.visible) {
    return null;
  }
  return <div className={styles.control}>
    <Button ref={trigger} type="button" className={styles.trigger}
            data-invalid={hasError || undefined}
            aria-invalid={hasError || undefined}
            aria-label={state.selected ? uiText("项目：{0}", [label]) : errorText ? `${label}，${errorText}` : label}
            title={errorText || (state.selected ? `${state.selected.name}\n${state.selected.directory}` : !state.existing
              ? uiText("未选择项目时，发送第一条消息会自动创建项目。") : undefined)}
            aria-haspopup="dialog" aria-expanded={open} disabled={state.disabled} onClick={() => setOpen(true)}>
      {hasError ? <IconAlertCircle size={15} data-icon-context="inherit"/> : <IconFolder size={15}/>}
      <span>{label}</span><IconChevronDown size={12}/>
    </Button>
    <span ref={measure} className={styles.measure} aria-hidden="true">{label}</span>
    {state.saving && <span className={styles.notice} role="status">{uiText("正在切换…")}</span>}
    {inlineReload && hasError && !open &&
      <Button type="button" className={`${styles.textButton} ${styles.inlineReload}`} onClick={state.reload}
              aria-label={errorText ? `${errorText}，${uiText("重新加载")}` : uiText("重新加载")}>{uiText("重新加载")}</Button>}
    {showError && hasError && !open &&
      <span className={styles.error} role="alert">{errorText}<Button type="button"
                                                                     className={styles.textButton}
                                                                     onClick={state.reload}>{uiText("重新加载")}</Button></span>}
    {open && <ProjectPickerDialog state={state} onClose={() => setOpen(false)}/>}
  </div>;
}
