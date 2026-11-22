"use client";

import {type CSSProperties, type ReactNode, useState} from "react";
import {IconChevronDown} from "@/components/ui/icons";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import {MeasuredDisclosureContent} from "@/components/ui/disclosure";
import {useT} from "@/lib/i18n/locale-provider";
import type {BlockStatus} from "../types/execution";
import {useExecutionDisclosure} from "../hooks/use-execution-disclosure";
import {ToolActivityIcon} from "./conversation-tool-block";
import {ConversationToolGlowLayer} from "./conversation-tool-glow-layer";
import {TOOL_BORDER_GLOW_CONFIG, toolBorderGlowPadding} from "./conversation-tool-border-glow-config";
import styles from "./conversation-tool-sequence.module.css";

const panelStyle = {"--tool-glow-padding": toolBorderGlowPadding(TOOL_BORDER_GLOW_CONFIG)} as CSSProperties;

/** 单项与多项使用同一容器，新增调用和收起分组不会重置已打开的工具详情。 */
export function ConversationToolSequence({count, status, failedCount, pendingIds, children}: {
  count: number; status: BlockStatus; failedCount: number; pendingIds: string[]; children: ReactNode;
}) {
  const t = useT();
  const attention = pendingIds.join("|");
  const [choice, setChoice] = useState<{attention: string; open: boolean | null}>({attention, open: null});
  const disclosure = useExecutionDisclosure(status, pendingIds);
  const single = count === 1;
  const open = single || ((choice.attention === attention ? choice.open : null) ?? disclosure.open);

  function choose(value: boolean) {
    setChoice({attention, open: value});
    disclosure.setOpen(value);
  }

  const statusText = status === "running" ? t("执行中") : status === "pending" ? t("等待执行")
    : status === "waiting_approval" ? t("等待确认") : status === "waiting_input" ? t("等待回答")
      : status === "cancelled" ? t("已停止") : status === "skipped" ? t("已跳过") : t("已完成");

  return <Collapsible className={styles.sequence} data-single={single || undefined} open={open} onOpenChange={choose}>
    {!single && <CollapsibleTrigger className={styles.trigger} aria-label={open
      ? t("收起 {0} 次工具调用", [count]) : t("展开 {0} 次工具调用", [count])}>
      <span className={styles.icon} aria-hidden="true"><ToolActivityIcon status={status}/></span>
      <span className={styles.title}>{t("工具调用 · {0} 次", [count])}</span>
      <span className={styles.state} data-failed={failedCount > 0 || undefined}>{failedCount > 0
        ? t("{0} 次未完成", [failedCount]) : statusText}</span>
      <IconChevronDown size={14} className={styles.chevron}/>
    </CollapsibleTrigger>}
    <CollapsibleContent className={`agenteam-disclosure-content ${styles.panel}`} style={panelStyle}
                        data-animate-height keepMounted inert={!open}>
      <MeasuredDisclosureContent>
        <ConversationToolGlowLayer viewportClassName={styles.scrollArea}>
          <div className={styles.body} onClickCapture={() => choose(true)} onFocusCapture={() => choose(true)}>{children}</div>
        </ConversationToolGlowLayer>
      </MeasuredDisclosureContent>
    </CollapsibleContent>
  </Collapsible>;
}
