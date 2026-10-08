"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ReactNode, useId, useMemo} from "react";
import {
  IconAlertCircle,
  IconCheck,
  IconChevronDown,
  IconClock,
  IconPlayerStop,
  IconSettings
} from "@/components/ui/icons";
import {Collapsible, CollapsibleContent, CollapsibleTrigger} from "@/components/ui/shadcn/collapsible";
import {MeasuredDisclosureContent} from "@/components/ui/disclosure";
import type {BlockStatus} from "../types/execution";
import {ToolCallPresentation} from "@/features/plugin/components/tool-call-presentation";
import {useExecutionDisclosure} from "../hooks/use-execution-disclosure";
import {ConversationToolBorderGlow} from "./conversation-tool-border-glow";
import {
  createToolBorderGlowStyle,
  resolveToolBorderGlowConfig,
  type ToolBorderGlowOptions
} from "./conversation-tool-border-glow-config";
import styles from "./conversation-tool-block.module.css";
import glowStyles from "./conversation-tool-border-glow.module.css";

export function ToolActivityIcon({status}: { status: BlockStatus }) {
  if (status === "running") {
    return <span className={styles.fluid} aria-hidden="true"><i/><i/><i/></span>;
  }
  if (status === "completed") {
    return <IconCheck size={19}/>;
  }
  if (status === "failed") {
    return <IconAlertCircle size={19}/>;
  }
  if (status === "waiting_approval" || status === "pending") {
    return <IconClock size={19}/>;
  }
  if (status === "cancelled" || status === "skipped") {
    return <IconPlayerStop size={18}/>;
  }
  return <IconSettings size={19}/>;
}

export function ConversationToolBlock({
                                        label,
                                        status,
                                        statusText,
                                        toolCallId,
                                        pendingIds = [],
                                        waitingForContent = false,
                                        glowConfig,
                                        children,
                                        subject,
                                        preview,
                                        notice,
                                        grouped = false
                                      }: {
  label: string;
  status: BlockStatus;
  statusText: string;
  toolCallId: string;
  pendingIds?: string[];
  waitingForContent?: boolean;
  glowConfig?: ToolBorderGlowOptions;
  children: ReactNode;
  subject?: ReactNode;
  preview?: ReactNode;
  notice?: ReactNode;
  grouped?: boolean;
}) {
  const uiText = useT();
  const titleId = useId();
  // 按具体参数复用配置，避免调用方重复创建同值对象时重启动画。
  const glowConfigKey = JSON.stringify(glowConfig ?? {});
  const glowSettings = useMemo(() => resolveToolBorderGlowConfig(JSON.parse(glowConfigKey) as ToolBorderGlowOptions), [glowConfigKey]);
  const {open, setOpen} = useExecutionDisclosure(status, pendingIds, false);
  const expanded = open && !waitingForContent;
  const active = status === "pending" || status === "running";
  const waitingForApproval = status === "waiting_approval";
  return <ToolCallPresentation compact onModeChange={() => setOpen(true)}
                               renderLayout={({modeButton, content}) => <Collapsible
                                 className={`${styles.card} ${glowStyles.frame}`} data-tool-call={toolCallId}
                                 data-state={status}
                                 style={createToolBorderGlowStyle(glowSettings)}
                                 data-active={active || undefined} data-waiting={waitingForContent || undefined}
                                 data-grouped={grouped || undefined}
                                 role="group" aria-labelledby={titleId} open={expanded} onOpenChange={setOpen}>
                                 {(active || waitingForApproval) &&
                                   <ConversationToolBorderGlow toolCallId={toolCallId} config={glowSettings}
                                                               staticTop={waitingForApproval}/>}
                                 <div className={styles.header}>
                                   <span className={styles.icon} aria-hidden="true"><ToolActivityIcon status={status}/></span>
                                   <span className={styles.heading}>
          <strong id={titleId} className={grouped ? styles.groupedTitle : styles.title} title={label}>{label}</strong>
                                     {subject && <span className={styles.subject}>{subject}</span>}
        </span>
                                   {statusText && <span className={status === "completed" ? "sr-only" : styles.status}
                                                        role="status">{statusText}</span>}
                                   {expanded && <div className={styles.modeControl}>{modeButton}</div>}
                                   <CollapsibleTrigger className={styles.trigger} disabled={waitingForContent}
                                                       aria-label={(expanded ? uiText("收起") : uiText("查看")) + label + uiText("详情")}>
                                     <span>{expanded ? uiText("收起") : uiText("详情")}</span><IconChevronDown
                                     className={styles.chevron} size={13}/>
                                   </CollapsibleTrigger>
                                 </div>
                                 {notice && <div className={styles.notice} role="alert">{notice}</div>}
                                 <CollapsibleContent className="agenteam-disclosure-content" data-animate-height
                                                     keepMounted={false} inert={!expanded}>
                                   <MeasuredDisclosureContent>
                                     <div className={styles.details}>
                                       {preview && <div className={styles.preview}>{preview}</div>}
                                       {content}
                                     </div>
                                   </MeasuredDisclosureContent>
                                 </CollapsibleContent>
                               </Collapsible>}>
    <div className={styles.body}>{waitingForContent ? null : children}</div>
  </ToolCallPresentation>;
}
