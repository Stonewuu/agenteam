"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {CollectionTable, type CollectionView} from "@/components/ui/collection-view";
import {IconBell, IconRobot, IconClock, IconHistory, IconCalendarClock} from "@/components/ui/icons";
import {OccurrenceStatus} from "./schedule-presentation";
import {ScheduleActionSummary, scheduleSummaryText} from "./schedule-action-summary";
import {timezoneLabel} from "@/lib/timezones";
import {scheduleRuleText, scheduleTime} from "../lib/schedule-display";
import type {Schedule} from "../types/schedule";
import {ScheduleActions} from "./schedule-actions";
import styles from "./schedule.module.css";

export function ScheduleCollection({view, plans, enterpriseId, permissions, onEdit, onRecords, onChanged, onDeleted}: {
  view: CollectionView;
  plans: Schedule[];
  enterpriseId: string;
  permissions: string[];
  onEdit: (plan: Schedule) => void;
  onRecords: (id: string) => void;
  onChanged: (message?: string) => void;
  onDeleted: () => void;
}) {
  const uiText = useT();

  function title(plan: Schedule) {
    return <Button type="button" className={styles.planTitle} onClick={() => onRecords(plan.id)}>{plan.name}</Button>;
  }

  function actions(plan: Schedule) {
    return <ScheduleActions compact enterpriseId={enterpriseId} plan={plan} permissions={permissions}
                            onEdit={() => onEdit(plan)} onRecords={() => onRecords(plan.id)} onChanged={onChanged}
                            onDeleted={onDeleted}/>;
  }

  if (view === "list") {
    return <CollectionTable label={uiText("计划列表")} className={styles.planTable}>
      <thead>
      <tr>
        <th scope="col">{uiText("计划名称")}</th>
        <th scope="col">{uiText("执行安排")}</th>
        <th scope="col">{uiText("执行内容")}</th>
        <th scope="col">{uiText("下次执行")}</th>
        <th scope="col">{uiText("执行结果")}</th>
        <th scope="col">{uiText("状态与操作")}</th>
      </tr>
      </thead>
      <tbody>{plans.map((plan) => <tr key={plan.id} data-schedule-id={plan.id}>
        <td className={styles.planNameCell}>{title(plan)}{scheduleSummaryText(plan) &&
          <p className={styles.tableSummary}>{scheduleSummaryText(plan)}</p>}{plan.pauseReason &&
          <p className={styles.tablePauseReason}>{plan.pauseReason}</p>}</td>
        <td><ScheduleRule plan={plan}/></td>
        <td><ScheduleActionSummary plan={plan}/></td>
        <td className={styles.nextRunCell}><ScheduleNextRun plan={plan}/></td>
        <td><ScheduleResult plan={plan}/></td>
        <td className={styles.planActionsCell}>{actions(plan)}</td>
      </tr>)}</tbody>
    </CollectionTable>;
  }
  return <div className={styles.scheduleGrid}>{plans.map((plan) => <article key={plan.id}
                                                                            className={`${styles.card} ${styles.scheduleCard}`}
                                                                            data-schedule-id={plan.id}
                                                                            aria-label={plan.name}>
    <div className={styles.cardHeading}><span className={styles.scheduleIcon}>{plan.action.type === "notification.send" ? <IconBell size={22}/> : <IconRobot size={22}/>}</span>
      <div>{title(plan)}<ScheduleRule plan={plan}/></div>
    </div>
    {scheduleSummaryText(plan) && <p className={styles.taskSummary}>{scheduleSummaryText(plan)}</p>}
    <dl className={styles.facts}>
      <div>
        <dt><IconCalendarClock size={14}/>{uiText("执行内容")}</dt>
        <dd><ScheduleActionSummary plan={plan}/></dd>
      </div>
      <div>
        <dt><IconClock size={14}/>{uiText("下次执行")}</dt>
        <dd><ScheduleNextRun plan={plan}/></dd>
      </div>
      <div>
        <dt><IconHistory size={14}/>{plan.activeOccurrence ? uiText("当前执行") : uiText("最近结果")}</dt>
        <dd><ScheduleResult plan={plan}/></dd>
      </div>
    </dl>
    {plan.pauseReason && <p className={styles.pauseReason}>{plan.pauseReason}</p>}
    <footer>{actions(plan)}</footer>
  </article>)}</div>;
}

function ScheduleRule({plan}: { plan: Schedule }) {
  const uiText = useT();
  return <p className={styles.rule}>{scheduleRuleText(plan, uiText)}<span
    className={styles.timezone}>{timezoneLabel(plan.timezone, uiText)}</span></p>;
}

function ScheduleNextRun({plan}: { plan: Schedule }) {
  const uiText = useT();
  return plan.enabled ? plan.nextRunAt ? <time
    dateTime={plan.nextRunAt}>{scheduleTime(plan.nextRunAt, plan.timezone, uiText.formatLocale)}</time> : uiText("没有后续执行时间") : uiText("计划已暂停");
}

function ScheduleResult({plan}: { plan: Schedule }) {
  const uiText = useT();
  const occurrence = plan.activeOccurrence ?? plan.latestOccurrence;
  return occurrence ? <OccurrenceStatus status={occurrence.status}/> : <span className={styles.result} data-state="empty">{uiText("尚无执行记录")}</span>;
}
