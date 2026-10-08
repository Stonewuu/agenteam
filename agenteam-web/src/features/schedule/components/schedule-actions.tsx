"use client";

import { useT } from "@/lib/i18n/locale-provider";
import { localizeCatalog } from "@/lib/i18n/translate";

import { Button } from "@/components/ui/button";
import { toast } from "@/components/ui/toast";

import { useState } from "react";
import { DialogCancel, Dialog, DialogActions, useDialogControl } from "@/components/ui/dialog";
import { Toggle } from "@/components/ui/toggle";
import { IconDots, IconPencil, IconPlayerPlay, IconHistory, IconPlayerPause, IconRefresh, IconTrash } from "@/components/ui/icons";
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from "@/components/ui/shadcn/dropdown-menu";
import { useFormAction } from "@/features/auth/hooks/use-form-action";
import { organizationPath } from "@/features/enterprise/api/organization-api";
import { MutationFeedback } from "@/features/workspace/components/mutation-feedback";
import { useBlockNavigation } from "@/features/workspace/components/navigation-guard";
import type { Occurrence, Schedule, ScheduleTimes } from "../types/schedule";
import { occurrenceNames } from "../lib/schedule-display";
import { ScheduleTimesPreview } from "./schedule-times";
import { ScheduleContent, ScheduleRecipients } from "./schedule-presentation";
import presentation from "./schedule-presentation.module.css";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule.module.css";

type Operation = "enable" | "pause" | "run" | "upgrade" | "delete";
const titles: Record<Operation, string> = { enable: "启用计划", pause: "暂停计划", run: "立即运行一次", upgrade: "更新员工版本", delete: "删除计划" };

export function ScheduleActions({ enterpriseId, plan, permissions, onEdit, onChanged, onDeleted, compact = false, onRecords }: {
  enterpriseId: string; plan: Schedule; permissions: string[]; onEdit: () => void; onChanged: (message?: string) => void; onDeleted: () => void; compact?: boolean; onRecords?: () => void;
}) {
  const uiText = useT();
  const [operation, setOperation] = useState<Operation | null>(null);
  const canManage = permissions.includes("schedule.manage");
  const usesAgent = plan.action.type === "agent.run";
  const canRun = !usesAgent || permissions.includes("agent.run");
  return <>{compact ? <div className={styles.compactActions}>
    <div className={styles.enableAction}>{canManage && (plan.enabled || canRun) && <Toggle label={uiText("启用{0}", [plan.name])} hideLabel checked={plan.enabled} onChange={() => setOperation(plan.enabled ? "pause" : "enable")} />}<span>{plan.enabled ? uiText("已启用") : uiText("已暂停")}</span></div>
    <div className={`${ui.actions} ${styles.actionButtons}`}>{onRecords && <Button className={ui.button} type="button" aria-label={uiText("查看{0}的执行记录", [plan.name])} onClick={onRecords}><IconHistory size={16} />{uiText("执行记录")}</Button>}
      {canManage && canRun && <><Button className={ui.button} type="button" aria-label={uiText("编辑{0}", [plan.name])} onClick={onEdit}><IconPencil size={16} />{uiText("编辑")}</Button><Button className={`${ui.button} ${styles.runButton}`} type="button" aria-label={uiText("运行一次{0}", [plan.name])} disabled={Boolean(plan.activeOccurrenceId)} onClick={() => setOperation("run")}><IconPlayerPlay size={16} />{uiText("运行一次")}</Button></>}
      {canManage && <DropdownMenu><DropdownMenuTrigger render={<Button type="button" className="icon-button" aria-label={uiText("{0}的更多操作", [plan.name])}><IconDots size={17} /></Button>} /><DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
        {canRun && usesAgent && <DropdownMenuItem onClick={() => setOperation("upgrade")}>{uiText("更新员工版本")}</DropdownMenuItem>}<DropdownMenuItem disabled={plan.enabled || Boolean(plan.activeOccurrenceId)} onClick={() => setOperation("delete")}>{uiText("删除计划")}</DropdownMenuItem>
      </DropdownMenuContent></DropdownMenu>}
    </div>
  </div> : <div className={styles.detailActions}><div className={ui.actions}>
    {canManage && canRun && <><Button className={ui.primary} disabled={Boolean(plan.activeOccurrenceId)} onClick={() => setOperation("run")}><IconPlayerPlay size={17}/>{uiText("立即运行一次")}</Button><Button className={ui.button} onClick={onEdit}><IconPencil size={16}/>{uiText("编辑")}</Button></>}
    {canManage && (plan.enabled || canRun) && <Button className={ui.button} onClick={() => setOperation(plan.enabled ? "pause" : "enable")}>{plan.enabled ? <IconPlayerPause size={16}/> : <IconPlayerPlay size={16}/>} {plan.enabled ? uiText("暂停计划") : uiText("启用计划")}</Button>}
    {canManage && canRun && usesAgent && <Button className={ui.button} onClick={() => setOperation("upgrade")}><IconRefresh size={16}/>{uiText("更新员工版本")}</Button>}
    </div>{canManage && <Button className={ui.danger} disabled={plan.enabled || Boolean(plan.activeOccurrenceId)} onClick={() => setOperation("delete")}><IconTrash size={16}/>{uiText("删除")}</Button>}
  </div>}{operation && <ScheduleActionDialog key={`${plan.id}:${operation}`} enterpriseId={enterpriseId} plan={plan} operation={operation}
    onClose={() => setOperation(null)} onChanged={onChanged} onDeleted={onDeleted} />}</>;
}

function ScheduleActionDialog({ enterpriseId, plan, operation, onClose, onChanged, onDeleted }: {
  enterpriseId: string; plan: Schedule; operation: Operation; onClose: () => void; onChanged: (message?: string) => void; onDeleted: () => void;
}) {
  const uiText = useT();
  const action = useFormAction(); const dialog = useDialogControl();
  const [times, setTimes] = useState<ScheduleTimes | null>(null);
  useBlockNavigation(false, action.busy);
  const root = organizationPath(enterpriseId, `/schedules/${encodeURIComponent(plan.id)}`);
  const description = operation === "pause" ? uiText("暂停“{0}”的后续定时运行。{1}", [plan.name, plan.activeOccurrenceId ? uiText("当前任务会继续执行，可在当前执行记录中停止。") : ""])
    : operation === "run" ? plan.action.type === "notification.send" ? uiText("立即发送“{0}”中的通知。请确认内容和接收人。", [plan.name])
      : uiText("立即让“{0}”执行一次“{1}”，使用第 {2} 版员工，计入本月用量。", [plan.agentName, plan.name, plan.agentVersionNo])
      : operation === "upgrade" ? uiText("“{0}”当前使用第 {1} 版员工。确认后，后续任务使用该员工当前发布的版本。", [plan.name, plan.agentVersionNo])
        : operation === "delete" ? uiText("删除“{0}”。已经产生的对话保留。", [plan.name]) : uiText("启用“{0}”前，请确认接下来的执行时间。", [plan.name]);
  const submit = () => void action.execute(async () => {
    if (operation === "enable" && !times) {
      const { frequency, localDate, localTime, weekdays, monthDay, timezone } = plan;
      const result = await action.mutation.run<ScheduleTimes>(organizationPath(enterpriseId, "/schedules/preview-times"), { method: "POST", body: { frequency, localDate, localTime, weekdays, monthDay, timezone } });
      if (!result.times.length) {
        throw new Error(uiText("该计划没有后续执行时间，请先编辑计划。"));
      }
      setTimes(result);
      return;
    }
    let message = "";
    if (operation === "run") {
      const result = await action.mutation.run<Occurrence>(`${root}/run`, { method: "POST" });
      message = result.status === "queued" ? uiText("本次任务已排队。") : uiText("本次任务{0}。{1}", [localizeCatalog(occurrenceNames, uiText)[result.status], result.reason ?? ""]);
    } else if (operation === "delete") {
      await action.mutation.run(root, { method: "DELETE", revision: plan.revision });
      dialog.close(() => {
        onDeleted();
        onClose();
        toast.success(uiText("计划已删除。"));
      });
      return;
    } else if (operation === "upgrade") {
      const result = await action.mutation.run<Schedule>(`${root}/upgrade-version`, { method: "POST", revision: plan.revision });
      message = result.agentVersionId === plan.agentVersionId ? uiText("计划已经使用当前发布版本。") : uiText("后续任务将使用第 {0} 版员工。", [result.agentVersionNo]);
    } else {
      await action.mutation.run(`${root}/enabled`, { method: "PATCH", revision: plan.revision, body: { enabled: operation === "enable" } });
      message = operation === "enable" ? uiText("计划已启用。") : uiText("计划已暂停。");
    }
    dialog.close(() => {
      onChanged(message);
      onClose();
    });
  }, "");
  const OperationIcon = operation === "delete" ? IconTrash : operation === "pause" ? IconPlayerPause : operation === "upgrade" ? IconRefresh : IconPlayerPlay;
  return <Dialog title={localizeCatalog(titles, uiText)[operation]} icon={<OperationIcon size={21}/>}
    wide={operation === "run" && plan.action.type === "notification.send"} onClose={onClose} dialogRef={dialog.ref} busy={action.busy}><p className={ui.description}>{description}</p>
    {operation === "run" && plan.action.type === "notification.send" && <div className={presentation.confirmationGrid}>
      <ScheduleContent notification title={String(plan.action.config.title ?? "")} body={String(plan.action.config.body ?? "")}/>
      <ScheduleRecipients recipients={plan.action.recipients}/>
    </div>}
    {times && <ScheduleTimesPreview value={times} />}
    <MutationFeedback action={action} onReload={() => {
      dialog.close(() => {
        onChanged();
        onClose();
      });
    }} />
    <DialogActions className={ui.footer}><DialogCancel className={ui.button} disabled={action.busy} >{uiText("取消")}</DialogCancel>
      <Button className={operation === "delete" ? ui.danger : ui.primary} disabled={action.busy} onClick={submit}>{action.busy ? uiText("正在处理…") : operation === "enable" && !times ? uiText("查看执行时间") : uiText("确认")}</Button></DialogActions>
  </Dialog>;
}
