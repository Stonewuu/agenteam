"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {useLayoutEffect, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {IconChevronDown, IconFolder} from "@/components/ui/icons";
import type {useConversationProject} from "../hooks/use-conversation-project";
import {ProjectPickerDialog} from "./project-picker-dialog";
import styles from "./project-picker.module.css";

export function ConversationProjectControl({state, showError = true}: {
  state: ReturnType<typeof useConversationProject>;
  showError?: boolean
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const measure = useRef<HTMLSpanElement>(null);
  const label = state.loading ? uiText("正在加载项目…") : state.selected?.name ?? (state.existing ? uiText("项目暂不可用") : uiText("新项目"));
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
    <Button ref={trigger} type="button" className={styles.trigger} aria-label={uiText("项目：{0}", [label])}
            title={state.selected ? `${state.selected.name}\n${state.selected.directory}` : undefined}
            aria-haspopup="dialog" aria-expanded={open} disabled={state.disabled} onClick={() => setOpen(true)}>
      <IconFolder size={15}/><span>{label}</span><IconChevronDown size={12}/>
    </Button>
    <span ref={measure} className={styles.measure} aria-hidden="true">{label}</span>
    {state.saving && <span className={styles.notice} role="status">{uiText("正在切换…")}</span>}
    {showError && state.error && !open &&
      <span className={styles.error} role="alert">{localizeUiMessage(state.error ?? "", uiText)}<Button type="button"
                                                                                                        className={styles.textButton}
                                                                                                        onClick={state.reload}>{uiText("重新加载")}</Button></span>}
    {open && <ProjectPickerDialog state={state} onClose={() => setOpen(false)}/>}
  </div>;
}
