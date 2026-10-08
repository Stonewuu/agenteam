"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Textarea} from "@/components/ui/textarea";

import {useState} from "react";
import Link from "next/link";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {
  IconCalendar,
  IconDots,
  IconMessages,
  IconPlayerPause,
  IconPlayerPlay,
  IconPlus,
  IconUserMinus,
  IconX
} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import type {Employee} from "../types/employee";
import ui from "@/components/ui/surface.module.css";
import styles from "./employee-actions.module.css";

type Operation = "hire" | "active" | "paused" | "terminated" | "withdraw";

export function EmployeeActions({enterpriseId, employee, permissions, onChanged, disabled = false}: {
  enterpriseId: string;
  employee: Employee;
  permissions: string[];
  onChanged: () => void;
  disabled?: boolean
}) {
  const uiText = useT();
  const [operation, setOperation] = useState<Operation | null>(null);
  const canManage = permissions.includes("agent.hire");
  const canRun = employee.canRun && permissions.includes("agent.run");
  const managesHire = canManage && Boolean(employee.hireId) && ["active", "paused"].includes(employee.hireStatus);
  const canWithdraw = canManage && employee.hireStatus === "pending" && Boolean(employee.applicationId);
  const canChat = canRun && permissions.includes("conversation.view");
  const canSchedule = canRun && Boolean(employee.hireId) && permissions.includes("schedule.manage") && permissions.includes("schedule.view");
  if (!canChat && !canSchedule && !managesHire && !canWithdraw && !(canManage && (employee.canHire || employee.canResume))) {
    return null;
  }
  return <>
    <div className={styles.actions} inert={disabled || operation !== null} aria-busy={disabled}>
      {canChat && <Link className={`${ui.primary} ${styles.primary}`}
                        href={`/enterprises/${encodeURIComponent(enterpriseId)}/new-task?agent=${encodeURIComponent(employee.agentId)}`}><IconMessages
        size={18}/>{uiText("开始对话")}</Link>}
      {canManage && employee.canHire && <Button type="button" className={`${ui.primary} ${styles.primary}`}
                                                onClick={() => setOperation("hire")}><IconPlus
        size={18}/>{employee.requiresApproval ? uiText("申请雇佣") : employee.hireStatus === "terminated" ? uiText("重新雇佣") : uiText("雇佣员工")}
      </Button>}
      {canManage && employee.canResume && <Button type="button" className={`${ui.primary} ${styles.primary}`}
                                                  onClick={() => setOperation("active")}><IconPlayerPlay
        size={18}/>{uiText("恢复雇佣")}</Button>}
      {canSchedule && <Link className={ui.button}
                            href={`/enterprises/${encodeURIComponent(enterpriseId)}/schedules?agent=${encodeURIComponent(employee.agentId)}`}><IconCalendar
        size={17}/>{uiText("创建计划")}</Link>}
      {canWithdraw && <Button type="button" className={ui.button} onClick={() => setOperation("withdraw")}><IconX
        size={17}/>{uiText("撤回申请")}</Button>}
      {managesHire && <DropdownMenu><DropdownMenuTrigger className={`${ui.button} ${styles.more}`}
                                                         aria-label={uiText("更多雇佣操作")}><IconDots
        size={19}/></DropdownMenuTrigger>
        <DropdownMenuContent className="agenteam-menu agenteam-popup" align="end" side="top">
          {employee.hireStatus === "active" && <><DropdownMenuItem
            onClick={() => setOperation("paused")}><IconPlayerPause size={17}/>{uiText("暂停雇佣")}
          </DropdownMenuItem><DropdownMenuSeparator/></>}
          <DropdownMenuItem variant="destructive" onClick={() => setOperation("terminated")}><IconUserMinus
            size={17}/>{uiText("解除雇佣")}</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>}
    </div>
    {operation && <HireAction key={`${operation}:${employee.hireRevision}:${employee.applicationRevision}`}
                              enterpriseId={enterpriseId} employee={employee} operation={operation}
                              onClose={() => setOperation(null)} onChanged={onChanged}/>}
  </>;
}

function HireAction({enterpriseId, employee, operation, onClose, onChanged}: {
  enterpriseId: string;
  employee: Employee;
  operation: Operation;
  onClose: () => void;
  onChanged: () => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const [note, setNote] = useState("");
  const [confirmClose, setConfirmClose] = useState(false);
  useBlockNavigation(Boolean(note), action.busy);
  const title = operation === "hire" ? employee.requiresApproval ? uiText("申请雇佣") : uiText("雇佣员工") : ({
    active: uiText("恢复雇佣"),
    paused: uiText("暂停雇佣"),
    terminated: uiText("解除雇佣"),
    withdraw: uiText("撤回申请")
  } as const)[operation];
  const description = operation === "hire" ? employee.requiresApproval
      ? uiText("申请雇佣“{0}”，申请有效期为 7 天。", [employee.name])
      : uiText("雇佣“{0}”后，将出现在我的员工中。", [employee.name])
    : operation === "active" ? uiText("恢复对“{0}”的雇佣。", [employee.name])
      : operation === "withdraw" ? uiText("撤回对“{0}”的申请，需要时可以重新申请。", [employee.name])
        : operation === "paused" ? uiText("暂停雇佣“{0}”后，将无法继续使用这位员工，已有对话记录保留。", [employee.name])
          : uiText("解除雇佣“{0}”后，将无法继续使用这位员工，已有对话记录保留。", [employee.name]);
  return <Dialog title={title} onClose={onClose} dialogRef={dialog.ref} onRequestClose={() => {
    if (note) {
      setConfirmClose(true);
      return false;
    }
    return true;
  }} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        if (operation === "hire") {
          await action.mutation.run(organizationPath(enterpriseId, "/hires"), {
            method: "POST",
            body: {agentId: employee.agentId, note}
          });
        } else if (operation === "withdraw") {
          await action.mutation.run(organizationPath(enterpriseId, `/hire-requests/${encodeURIComponent(employee.applicationId!)}/withdraw`), {
            method: "POST",
            revision: employee.applicationRevision!
          });
        } else {
          await action.mutation.run(organizationPath(enterpriseId, `/hires/${encodeURIComponent(employee.hireId!)}`), {
            method: "PATCH",
            revision: employee.hireRevision!,
            body: {status: operation}
          });
        }
        dialog.close(() => {
          onChanged();
          onClose();
        });
      });
    }}>
      <p className={ui.description}>{description}</p>
      {operation === "hire" && employee.requiresApproval &&
        <label className={ui.field}><span>{uiText("申请说明")}</span><Textarea className={ui.textarea} value={note}
                                                                               maxLength={500}
                                                                               onChange={(event) => setNote(event.target.value)}/></label>}
      <MutationFeedback action={action} onReload={() => {
        dialog.close(() => {
          onChanged();
          onClose();
        });
      }}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                         disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button className={ui.primary}
                disabled={action.busy}>{action.busy ? uiText("正在提交…") : title}</Button></DialogActions>
    </DialogForm>
    {confirmClose && <Dialog variant="discard" title={uiText("放弃未提交的申请说明？")}
                             onClose={() => setConfirmClose(false)}><DialogActions className={ui.footer}>
      <DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogCancel className={ui.danger}
                                                                                           onClick={() => dialog.close(onClose)}>{uiText("放弃修改")}</DialogCancel></DialogActions></Dialog>}
  </Dialog>;
}
