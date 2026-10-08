"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {ScheduleActionSummary} from "./schedule-action-summary";

import {timezoneLabel} from "@/lib/timezones";
import {toast} from "@/components/ui/toast";

import {Button} from "@/components/ui/button";

import {PageHeader} from "@/components/ui/page-header";

import {useEffect, useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {Pagination, QueryState} from "@/components/ui/query-state";
import type {Occurrence, Schedule} from "../types/schedule";
import {scheduleRuleText, scheduleTime} from "../lib/schedule-display";
import {ScheduleEditor} from "./schedule-editor";
import {ScheduleActions} from "./schedule-actions";
import {OccurrenceCard} from "./occurrence-card";
import {ScheduleContent, ScheduleRecipients} from "./schedule-presentation";
import {DetailHeading, DetailStatus} from "@/components/ui/detail-section";
import {IconArrowLeft, IconRefresh, IconCalendarClock, IconClock, IconHistory, IconBell, IconRobot, IconCheck, IconPlayerPause, IconAlertCircle} from "@/components/ui/icons";
import {NotificationOpenedMarker} from "@/features/notification/components/notification-opened-marker";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule-presentation.module.css";

export function ScheduleDetailPage({enterpriseId, scheduleId}: { enterpriseId: string; scheduleId: string }) {
  return <EnterpriseGate enterpriseId={enterpriseId} permission="schedule.view">{({user, context}) => <ScheduleDetail
    key={`${enterpriseId}:${scheduleId}`} user={user} context={context} scheduleId={scheduleId}/>}</EnterpriseGate>;
}

function ScheduleDetail({user, context, scheduleId}: {
  user: IdentityUser;
  context: EnterpriseContext;
  scheduleId: string
}) {
  const uiText = useT();
  const enterpriseId = context.enterprise.id;
  const router = useRouter();
  const [refresh, setRefresh] = useState(0);
  const [editing, setEditing] = useState<Schedule | null>(null);
  const path = organizationPath(enterpriseId, `/schedules/${encodeURIComponent(scheduleId)}`);
  const detail = useApiQuery<Schedule>(path, refresh);
  const list = useApiPage<Occurrence>(`${path}/occurrences`, refresh, 0);
  const changed = (message?: string) => {
    if (message !== undefined) {
      toast.success(message);
    }
    setRefresh((value) => value + 1);
  };
  useEffect(() => {
    if (editing) {
      return;
    }
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        setRefresh((value) => value + 1);
      }
    }, 10000);
    return () => window.clearInterval(timer);
  }, [editing]);
  const plan = detail.data;
  return <PlatformShell user={user} context={context} area="user" title={plan?.name ?? uiText("计划详情")}>
    <NotificationOpenedMarker enterpriseId={enterpriseId} targetType="schedule" targetId={plan?.id ?? null}/>
    <div className={styles.detail}><PageHeader title={plan?.name ?? uiText("计划详情")} actions={<><Link className={ui.button}
      href={enterprisePath(enterpriseId, "/schedules")}><IconArrowLeft size={16}/>{uiText("我的计划")}</Link><Button
      className={ui.button} disabled={detail.loading} onClick={() => changed()}><IconRefresh size={16}/>{uiText("刷新")}</Button></>}/>
    <QueryState {...detail} hasData={Boolean(plan)} empty={uiText("计划暂不可查看。")}>{plan && <>
      <section className={styles.overview} aria-label={uiText("计划概览")}>
        <div className={styles.overviewHead}><div className={styles.overviewLabel}>
          {plan.action.type === "notification.send" ? <IconBell size={20}/> : <IconRobot size={20}/>}
          <strong><ScheduleActionSummary plan={plan}/></strong>
          {plan.agentVersionNo != null && <span>{uiText("第 {0} 版", [plan.agentVersionNo])}</span>}
        </div><DetailStatus tone={plan.enabled ? "success" : "neutral"} icon={plan.enabled ? <IconCheck size={14}/> : <IconPlayerPause size={14}/>}>
          {plan.enabled ? uiText("已启用") : uiText("未启用")}</DetailStatus></div>
        <dl className={styles.scheduleFacts}>
          <div>
            <dt><IconCalendarClock size={16}/>{uiText("执行规则")}</dt>
            <dd>{scheduleRuleText(plan, uiText)}<small>{timezoneLabel(plan.timezone, uiText)}</small></dd>
          </div>
          <div>
            <dt><IconClock size={16}/>{uiText("下一次执行")}</dt>
            <dd>{scheduleTime(plan.nextRunAt, plan.timezone, uiText.formatLocale)}</dd>
          </div>
        </dl>
        {plan.action.type === "agent.run" && <div className={styles.metadata}><IconRefresh size={15}/>{uiText("失败后自动再试")} ·
          {plan.maxRetries ? uiText("最多 {0} 次", [plan.maxRetries]) : uiText("不再试")}</div>}
        {plan.pauseReason && <p className={styles.reason}><IconAlertCircle size={16}/>{plan.pauseReason}</p>}
        <ScheduleActions enterpriseId={enterpriseId} plan={plan}
                                                         permissions={context.permissions}
                                                         onEdit={() => setEditing(plan)} onChanged={changed}
          onDeleted={() => router.replace(enterprisePath(enterpriseId, "/schedules"))}/>
      </section>
      <div className={plan.action.type === "notification.send" ? styles.detailsGrid : styles.history}>
        <ScheduleContent notification={plan.action.type === "notification.send"}
          title={plan.action.type === "notification.send" ? String(plan.action.config.title ?? "") : undefined}
          body={plan.action.type === "notification.send" ? String(plan.action.config.body ?? "") : plan.inputText ?? ""}/>
        {plan.action.type === "notification.send" && <ScheduleRecipients recipients={plan.action.recipients}/>}
      </div>
      {plan.activeOccurrence &&
        <section className={styles.history}><DetailHeading level="h2" icon={<IconClock size={20}/>} title={uiText("当前执行")}/><OccurrenceCard key={plan.activeOccurrence.id}
                                                                                         enterpriseId={enterpriseId}
                                                                                         value={plan.activeOccurrence}
                                                                                         timezone={plan.timezone} active
                                                                                         permissions={context.permissions}
                                                                                         onChanged={changed}/>
        </section>}
      <section className={styles.history}><DetailHeading level="h2" icon={<IconHistory size={20}/>} title={uiText("执行记录")}
        description={uiText("时间按 ") + timezoneLabel(plan.timezone, uiText) + uiText(" 显示。")}/>
        <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("尚无执行记录。")}>
          <div className={styles.stack}>{list.data?.items.map((item) => <OccurrenceCard key={item.id}
                                                                                       enterpriseId={enterpriseId}
                                                                                       value={item}
                                                                                       timezone={plan.timezone}
                                                                                       permissions={context.permissions}
                                                                                       onChanged={changed}/>)}</div>
        </QueryState>
        <Pagination {...list} hasMore={list.data?.hasMore}/>
      </section>
    </>}</QueryState></div>
    {editing && <ScheduleEditor enterpriseId={enterpriseId} timezone={context.enterprise.timezone} initial={editing}
                                showMarket={context.permissions.includes("agent.market_view")}
                                onClose={() => setEditing(null)}
                                onReload={() => {
                                  setEditing(null);
                                  changed();
                                }} onSaved={() => {
      setEditing(null);
      changed(uiText("计划已保存。"));
    }}/>}
  </PlatformShell>;
}
