"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import Link from "next/link";
import {useState} from "react";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {Occurrence} from "../types/schedule";
import {occurrenceActive, scheduleTime} from "../lib/schedule-display";
import {IconCalendarClock, IconPlayerPlay, IconMessages, IconArrowRight, IconPlayerStop, IconAlertCircle} from "@/components/ui/icons";
import {OccurrenceStatus} from "./schedule-presentation";
import {OccurrenceDetailDialog} from "./occurrence-detail-dialog";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule-presentation.module.css";

export function OccurrenceCard({enterpriseId, value, timezone, active = false, permissions, onChanged}: {
  enterpriseId: string;
  value: Occurrence;
  timezone: string;
  active?: boolean;
  permissions: string[];
  onChanged: (message?: string) => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [details, setDetails] = useState(false);
  const cancelRequested = Boolean(value.actionResult.cancelRequested);
  return <><article className={styles.occurrence}>
    <div className={styles.occurrenceHeading}><h3>{value.triggerKind === "manual" ? <IconPlayerPlay size={18}/> : <IconCalendarClock size={18}/>}
      {value.triggerKind === "manual" ? uiText("手动运行") : uiText("定时运行")}</h3>
      <OccurrenceStatus status={value.status} stopping={cancelRequested && active}/></div>
    <dl className={styles.occurrenceFacts}>
      <div>
        <dt>{uiText("触发时间")}</dt>
        <dd>{scheduleTime(value.scheduledFor, timezone, uiText.formatLocale)}</dd>
      </div>
      <div>
        <dt>{uiText("实际开始")}</dt>
        <dd>{scheduleTime(value.startedAt, timezone, uiText.formatLocale)}</dd>
      </div>
      <div>
        <dt>{uiText("结束时间")}</dt>
        <dd>{scheduleTime(value.finishedAt, timezone, uiText.formatLocale)}</dd>
      </div>
      {value.attemptCount > 0 && <div>
        <dt>{uiText("尝试次数")}</dt>
        <dd>{value.attemptCount}</dd>
      </div>}</dl>
    {value.reason && <p className={styles.reason}><IconAlertCircle size={16}/>{value.reason}</p>}
    <div className={styles.occurrenceFooter}><div className={ui.actions}>{value.conversationId && permissions.includes("conversation.view") &&
      <Link className={ui.button}
            href={enterprisePath(enterpriseId, `/conversations/${encodeURIComponent(value.conversationId)}`)}><IconMessages size={16}/>{uiText("查看对话")}</Link>}
      <Button className={ui.button} onClick={() => setDetails(true)}>{uiText("本次执行详情")}<IconArrowRight size={16}/></Button></div>
      {active && permissions.includes("schedule.manage") &&
        <Button className={ui.danger} disabled={action.busy || cancelRequested}
                onClick={() => void action.execute(async () => {
                  const result = await action.mutation.run<Occurrence>(organizationPath(enterpriseId,
                    `/schedules/${encodeURIComponent(value.scheduleId)}/occurrences/${encodeURIComponent(value.id)}/cancel`), {method: "POST"});
                  onChanged(occurrenceActive(result) ? uiText("已提交停止请求，正在等待本次结果。") : value.actionType === "notification.send"
                    ? uiText("本次操作已结束，已发送的通知保留。") : uiText("本次执行已结束。"));
                }, "")}><IconPlayerStop size={16}/>{cancelRequested ? uiText("正在停止…") : uiText("停止本次执行")}</Button>}</div>
    <MutationFeedback action={action}/>
  </article>{details && <OccurrenceDetailDialog enterpriseId={enterpriseId} occurrence={value} timezone={timezone}
    onClose={() => setDetails(false)} onChanged={() => onChanged()}/>}</>;
}
