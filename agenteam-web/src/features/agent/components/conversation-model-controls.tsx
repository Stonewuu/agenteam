"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {type CSSProperties, useCallback, useEffect, useLayoutEffect, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {IconCheck, IconChevronDown} from "@/components/ui/icons";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/shadcn/dropdown-menu";
import {
  type ReasoningEffort,
  reasoningEffortLabels,
  sameModelSelection
} from "@/features/modelprofile/lib/reasoning-effort";
import type {useConversationModel} from "../hooks/use-conversation-model";
import styles from "./conversation-model-controls.module.css";

type ModelState = ReturnType<typeof useConversationModel>;

/** 二级菜单同时选择模型与思考强度，一次提交后保留原入口焦点。 */
export function ConversationModelControls({state}: { state: ModelState }) {
  const uiText = useT();
  const trigger = useRef<HTMLButtonElement>(null);
  const measure = useRef<HTMLSpanElement>(null);
  const restoreFocus = useRef(false);
  const [reasoningSpace, setReasoningSpace] = useState(0);
  const reserveReasoningSpace = useCallback((element: HTMLDivElement | null) => {
    if (element) {
      // 为右侧子菜单预留真实宽度，当前菜单关闭前保留位置，避免来回移动。
      setReasoningSpace((previous) => Math.max(previous, element.offsetWidth + 6));
    }
  }, []);
  const visible = state.visible && (state.loading || state.loadError || state.configurable);
  const name = state.loading ? uiText("正在加载模型…") : state.model?.name ?? (state.selection ? uiText("原模型不可用") : uiText("选择模型"));
  const effort = state.loading ? "" : state.selection?.reasoningEffort
    ? localizeCatalog(reasoningEffortLabels, uiText)[state.selection.reasoningEffort] : state.model?.reasoningEfforts.length ? uiText("默认") : "";
  const label = effort ? `${name} · ${effort}` : name;
  useLayoutEffect(() => {
    const content = measure.current;
    const button = trigger.current;
    if (!content || !button) {
      return;
    }
    const resize = () => {
      const style = getComputedStyle(button);
      const padding = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
      button.style.setProperty("--model-width", `${Math.ceil(content.getBoundingClientRect().width + padding)}px`);
    };
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(content);
    return () => observer.disconnect();
  }, [visible]);
  useEffect(() => {
    if (state.saving || !restoreFocus.current) {
      return;
    }
    const frame = requestAnimationFrame(() => {
      restoreFocus.current = false;
      if (document.activeElement === document.body) {
        trigger.current?.focus({preventScroll: true});
      }
    });
    return () => cancelAnimationFrame(frame);
  }, [state.saving, state.selection]);
  if (!visible) {
    return null;
  }
  const defaults = state.options.find((option) => option.id === state.defaults?.modelProfileId);
  const canReset = defaults?.available && (!state.defaults?.reasoningEffort || defaults.reasoningEfforts.includes(state.defaults.reasoningEffort));

  function choose(id: string, level: ReasoningEffort | null) {
    restoreFocus.current = true;
    state.changeModel(id, level);
  }

  const identity = <><span className={styles.name}>{name}</span>{effort &&
    <span className={styles.effort}>· {effort}</span>}<IconChevronDown size={12}/></>;
  return <DropdownMenu onOpenChange={(open) => {
    if (open) {
      setReasoningSpace(0);
    }
  }}>
    <DropdownMenuTrigger
      render={<Button ref={trigger} type="button" className={styles.trigger} data-composer-part="model"
                      disabled={state.disabled || state.loading}
                      aria-label={uiText("模型与思考强度：{0}", [label])}
                      title={state.saving ? uiText("正在保存模型选择…") : label}
                      aria-busy={state.loading || state.saving || undefined}>
        {identity}<span ref={measure} className={styles.measure} aria-hidden="true">{identity}</span>
      </Button>}/>
    <DropdownMenuContent className={styles.menu} side="top" align="end" sideOffset={8}
                         collisionPadding={{top: 12, bottom: 12, left: 12, right: 12 + reasoningSpace}}
                         collisionAvoidance={{side: "flip", align: "shift"}}
                         positionerClassName={reasoningSpace ? styles.shiftPosition : undefined}
                         style={{"--reasoning-space": `${reasoningSpace}px`} as CSSProperties}>
      <DropdownMenuGroup>
        <DropdownMenuLabel>{uiText("模型")}</DropdownMenuLabel>
        {state.options.map((option) => {
          const selected = option.id === state.selection?.modelProfileId;
          const content = <><span
            className={styles.optionText}><span>{option.name}</span>{!option.available && option.unavailableReason &&
            <small>{uiText(option.unavailableReason)}</small>}</span>
            {selected && <IconCheck size={14} aria-label={uiText("当前模型")}/>}</>;
          return option.reasoningEfforts.length > 0 && option.available ? <DropdownMenuSub key={option.id}>
              <DropdownMenuSubTrigger className={styles.option}
                                      disabled={state.disabled}>{content}</DropdownMenuSubTrigger>
              <DropdownMenuSubContent ref={reserveReasoningSpace} className={styles.submenu} side="right" sideOffset={12}
                                      collisionPadding={12}
                                      collisionAvoidance={{side: "shift", align: "shift", fallbackAxisSide: "none"}}>
                <DropdownMenuGroup>
                  <DropdownMenuLabel>{uiText("思考强度")}</DropdownMenuLabel>
                  <DropdownMenuRadioGroup aria-label={uiText("{0}的思考强度", [option.name])}
                                          value={selected ? state.selection?.reasoningEffort ?? "default" : ""}
                                          onValueChange={(value) => choose(option.id, value === "default" ? null : value as ReasoningEffort)}>
                    <DropdownMenuRadioItem className={styles.option} value="default" closeOnClick
                                           disabled={state.disabled}>{uiText("默认")}</DropdownMenuRadioItem>
                    {option.reasoningEfforts.map((level) => <DropdownMenuRadioItem key={level} className={styles.option}
                                                                                   value={level} closeOnClick
                                                                                   disabled={state.disabled}>
                      {localizeCatalog(reasoningEffortLabels, uiText)[level]}
                    </DropdownMenuRadioItem>)}
                  </DropdownMenuRadioGroup>
                </DropdownMenuGroup>
              </DropdownMenuSubContent>
            </DropdownMenuSub> :
            <DropdownMenuItem className={styles.option} key={option.id} disabled={!option.available || state.disabled}
                              onClick={() => choose(option.id, null)}>{content}</DropdownMenuItem>;
        })}
      </DropdownMenuGroup>
      {canReset && !sameModelSelection(state.selection, state.defaults) && <><DropdownMenuSeparator/>
        <DropdownMenuItem className={styles.option} disabled={state.disabled} onClick={() => {
          restoreFocus.current = true;
          state.reset();
        }}>{uiText("恢复员工默认设置")}</DropdownMenuItem>
      </>}
    </DropdownMenuContent>
  </DropdownMenu>;
}

export function ConversationModelFeedback({state}: { state: ModelState }) {
  const uiText = useT();
  return <AnimatedHeight>{state.visible && state.error &&
    <p className={styles.error} role="alert">{localizeUiMessage(state.error ?? "", uiText)}
      {state.loadError && <Button type="button" onClick={state.reload}>{uiText("重新加载")}</Button>}
    </p>}</AnimatedHeight>;
}
