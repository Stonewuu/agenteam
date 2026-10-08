"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Disclosure, DisclosureSummary} from "@/components/ui/disclosure";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {useId, useState} from "react";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {RunApproval} from "../types/execution";
import styles from "./run-approval.module.css";
import {localizeSavedToolLabel} from "@/features/plugin/lib/tool-display-name";
import ui from "@/components/ui/surface.module.css";
import {
  ToolCallPresentation,
  ToolPayload,
  ToolPayloadContent
} from "@/features/plugin/components/tool-call-presentation";
import {approvalDisplayText} from "../lib/conversation-display";

const confirmationStatus = {
  pending: "等待确认",
  approved: "已确认",
  rejected: "已拒绝",
  expired: "已过期",
  revoked: "已失效"
};

export function RunApprovalCard({enterprise, approval, onChanged, onDecision, compact = false, toolLabel}: {
  enterprise: string;
  approval: RunApproval;
  onChanged: () => void;
  onDecision?: (approval: RunApproval) => void;
  compact?: boolean;
  toolLabel?: string;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [reason, setReason] = useState("");
  const [showReason, setShowReason] = useState(false);
  const reasonId = useId();
  const decide = (decision: "approve" | "reject") => void action.execute(async () => {
    try {
      const result = await action.mutation.run<RunApproval>(organizationPath(enterprise, `/approvals/${encodeURIComponent(approval.id)}/decision`),
        {
          method: "POST",
          revision: approval.revision,
          body: {
            decision,
            requestHash: approval.requestHash, ...(decision === "reject" && reason.trim() ? {reason} : {})
          }
        });
      onDecision?.(result);
    } finally {
      onChanged();
    }
  }, "");
  const pending = approval.status === "pending";
  const {description, notice} = approvalDisplayText(approval, compact);
  const title = localizeSavedToolLabel(approval.summary.title, uiText);
  const targetLabel = localizeSavedToolLabel(approval.summary.target, uiText);
  const normalizedTitle = (value: string) => value.replace(/[\s：:/·]+/g, "");
  const target = targetLabel && normalizedTitle(targetLabel) !== normalizedTitle(toolLabel ?? title)
    ? <p className={styles.target}>{targetLabel}</p> : null;
  const payload = <ToolPayloadContent value={approval.summary.content} showAllFields/>;
  const effect = approval.summary.irreversibleEffect &&
    <p className={ui.error}>{approval.summary.irreversibleEffect}</p>;
  const buttons = <><Button className={ui.primary} type="button" disabled={action.busy}
                            onClick={() => decide("approve")}>{action.busy ? uiText("正在处理…") : uiText("确认执行")}</Button>
    <Button className={ui.button} type="button" disabled={action.busy}
            onClick={() => decide("reject")}>{uiText("拒绝")}</Button></>;
  const reasonInput = <label className={ui.field}><span>{uiText("拒绝原因（可选）")}</span><Input className={ui.input}
                                                                                                maxLength={500}
                                                                                                value={reason}
                                                                                                disabled={action.busy}
                                                                                                onChange={(event) => setReason(event.target.value)}/></label>;
  if (compact) {
    return <section className={styles.inline} data-state={approval.status} aria-label={uiText("工具执行确认")}>
      {pending && target}
      {!pending && approval.status !== "approved" &&
        <span className={styles.decision}>{localizeCatalog(confirmationStatus, uiText)[approval.status]}</span>}
      {pending && description && <p className={styles.description}>{description}</p>}
      <ToolPayload title={uiText("调用参数")} part="input" value={approval.summary.content}/>
      {effect}
      {pending && <>
        {notice && <p className={styles.notice}>{uiText(notice)}</p>}
        <div className={styles.actions}>{buttons}<Button className={styles.reasonToggle} type="button"
                                                         disabled={action.busy} aria-expanded={showReason}
                                                         aria-controls={reasonId}
                                                         onClick={() => setShowReason(!showReason)}>{uiText("拒绝原因")}</Button>
        </div>
        <p className={styles.expires}>{uiText("请在 ")}<EnterpriseDateTime
          value={approval.expiresAt}/>{uiText(" 前处理。")}</p>
        <div id={reasonId} hidden={!showReason}>{reasonInput}</div>
      </>}
      <MutationFeedback action={action}/>
    </section>;
  }
  return <section className={styles.card} data-state={approval.status} aria-label={uiText("操作确认")}>
    <header><h3>{title}</h3><span>{localizeCatalog(confirmationStatus, uiText)[approval.status]}</span></header>
    {target}
    {description && <p className={ui.description}>{description}</p>}
    {notice && <p className={styles.notice}>{uiText(notice)}</p>}
    <ToolCallPresentation><Disclosure open={approval.status === "pending"}
                                      keepMounted={false}><DisclosureSummary>{uiText("查看操作内容")}</DisclosureSummary>{payload}
    </Disclosure></ToolCallPresentation>
    {approval.summary.irreversibleEffect && <p className={ui.error}>{approval.summary.irreversibleEffect}</p>}
    {approval.status === "pending" && <><p className={ui.description}>{uiText("请在 ")}<EnterpriseDateTime
      value={approval.expiresAt}/>{uiText(" 前处理。")}</p>
      {reasonInput}
      <div className={ui.actions}>{buttons}</div>
    </>}
    <MutationFeedback action={action}/>
  </section>;
}
