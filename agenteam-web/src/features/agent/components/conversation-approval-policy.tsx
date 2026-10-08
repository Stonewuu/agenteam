"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {useLayoutEffect, useRef} from "react";
import {Button} from "@/components/ui/button";
import {IconChevronDown, IconShield, IconShieldCheck, IconUnlock} from "@/components/ui/icons";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import type {ToolApprovalPolicy} from "../types/execution";
import styles from "./conversation-approval-policy.module.css";

const policies: { value: ToolApprovalPolicy; label: string; description: string; icon: typeof IconShield }[] = [
  {value: "default", label: "默认", description: "只读免确认，其余操作需确认。", icon: IconShield},
  {value: "auto_approve", label: "自动批准", description: "普通读写免确认，其余需确认。", icon: IconShieldCheck},
  {value: "full_access", label: "完全访问", description: "所有工具直接执行，无需确认。", icon: IconUnlock},
];

export function ConversationApprovalPolicy({value, onChange, disabled, saving, running}: {
  value: ToolApprovalPolicy;
  onChange: (value: ToolApprovalPolicy) => void;
  disabled: boolean;
  saving: boolean;
  running: boolean;
}) {
  const uiText = useT();
  const selected = localizeCatalog(policies, uiText).find((policy) => policy.value === value) ?? localizeCatalog(policies, uiText)[0];
  const PolicyIcon = selected.icon;
  const trigger = useRef<HTMLButtonElement>(null);
  const measure = useRef<HTMLSpanElement>(null);
  useLayoutEffect(() => {
    const label = measure.current;
    const button = trigger.current;
    if (!label || !button) {
      return;
    }
    const resize = () => button.style.setProperty("--policy-label-width", `${Math.ceil(label.getBoundingClientRect().width)}px`);
    resize();
    const observer = new ResizeObserver(resize);
    observer.observe(label);
    return () => observer.disconnect();
  }, [selected.label]);
  return <DropdownMenu>
    <DropdownMenuTrigger
      render={<Button ref={trigger} className={styles.trigger} data-composer-part="policy" type="button"
                      disabled={disabled} aria-busy={saving || undefined}
                      aria-label={uiText("工具审批策略：{0}", [selected.label])}
                      title={saving ? uiText("正在保存工具审批策略…") : running ? uiText("当前任务结束后可修改工具审批策略") : uiText("工具审批策略：{0}", [selected.label])}
                      data-policy={value}>
        <PolicyIcon size={16}/><span className={styles.label}>{selected.label}</span><IconChevronDown
        className={styles.chevron} size={12}/>
        <span ref={measure} className={styles.measure} aria-hidden="true">{selected.label}</span>
      </Button>}/>
    <DropdownMenuContent className={styles.menu} side="top" align="end" sideOffset={8}>
      <DropdownMenuRadioGroup value={value} onValueChange={(next) => onChange(next as ToolApprovalPolicy)}
                              aria-label={uiText("工具审批策略")}>
        {localizeCatalog(policies, uiText).map((policy) => {
          const OptionIcon = policy.icon;
          return <DropdownMenuRadioItem key={policy.value} value={policy.value} className={styles.option} closeOnClick>
            <OptionIcon className={styles.optionIcon} size={18}/>
            <span
              className={styles.optionText}><strong>{policy.label}</strong><small>{policy.description}</small></span>
          </DropdownMenuRadioItem>;
        })}
      </DropdownMenuRadioGroup>
    </DropdownMenuContent>
  </DropdownMenu>;
}
