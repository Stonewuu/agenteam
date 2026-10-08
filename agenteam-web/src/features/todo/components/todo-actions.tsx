"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";
import {toast} from "@/components/ui/toast";
import {Textarea} from "@/components/ui/textarea";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {ApiOptionPicker} from "@/features/workspace/components/api-option-picker";
import type {Todo, TodoAction} from "../types/todo";
import ui from "@/components/ui/surface.module.css";

type Operation = Exclude<TodoAction, "edit" | "start" | "complete">;
const labels: Record<TodoAction, string> = {
  edit: "编辑",
  transfer: "转交",
  delete: "删除",
  start: "开始处理",
  complete: "完成",
  cancel: "取消待办",
  reopen: "重新打开"
};
const states = {start: "in_progress", complete: "completed", cancel: "cancelled", reopen: "pending"};

export function TodoActions({enterpriseId, todo, onEdit, onChanged, onDeleted}: {
  enterpriseId: string; todo: Todo; onEdit: () => void; onChanged: (message?: string) => void; onDeleted: () => void;
}) {
  const uiText = useT();
  const [operation, setOperation] = useState<Operation | null>(null);
  const action = useFormAction();
  const path = organizationPath(enterpriseId, `/todos/${encodeURIComponent(todo.id)}`);
  return <>
    <div className={ui.actions}>{todo.allowedActions.map((item) => <Button key={item}
                                                                           className={item === "complete" ? ui.primary : item === "delete" ? ui.danger : ui.button}
                                                                           type="button" disabled={action.busy}
                                                                           onClick={() => {
                                                                             if (item === "edit") {
                                                                               onEdit();
                                                                             } else if (item === "complete" || item === "start") {
                                                                               void action.execute(async () => {
                                                                                 await action.mutation.run(`${path}/status`, {
                                                                                   method: "PATCH",
                                                                                   revision: todo.revision,
                                                                                   body: {
                                                                                     status: states[item],
                                                                                     reason: ""
                                                                                   }
                                                                                 });
                                                                                 onChanged(item === "complete" ? uiText("待办已完成。") : uiText("已开始处理。"));
                                                                               }, "");
                                                                             } else {
                                                                               setOperation(item);
                                                                             }
                                                                           }}>{localizeCatalog(labels, uiText)[item]}</Button>)}</div>
    <MutationFeedback action={action} onReload={() => {
      action.resetFeedback();
      onChanged();
    }}/>
    {operation &&
      <TodoActionDialog enterpriseId={enterpriseId} todo={todo} operation={operation} onClose={() => setOperation(null)}
                        onChanged={onChanged} onDeleted={onDeleted}/>}
  </>;
}

function TodoActionDialog({enterpriseId, todo, operation, onClose, onChanged, onDeleted}: {
  enterpriseId: string;
  todo: Todo;
  operation: Operation;
  onClose: () => void;
  onChanged: (message?: string) => void;
  onDeleted: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [reason, setReason] = useState("");
  const [owner, setOwner] = useState({id: todo.owner.id, name: todo.owner.displayName});
  const [picker, setPicker] = useState(false);
  const [discard, setDiscard] = useState(false);
  const dirty = Boolean(reason) || owner.id !== todo.owner.id;
  useBlockNavigation(dirty, action.busy);
  const dialog = useDialogControl();
  const root = organizationPath(enterpriseId, "/todos");
  const params = new URLSearchParams({todoId: todo.id});
  if (todo.teamId) {
    params.set("teamId", todo.teamId);
  }
  return <Dialog title={localizeCatalog(labels, uiText)[operation]} onClose={onClose} dialogRef={dialog.ref}
                 onRequestClose={() => {
                   if (dirty) {
                     setDiscard(true);
                     return false;
                   }
                   return true;
                 }} busy={action.busy}>
    <p className={ui.description}>{operation === "delete" ? uiText("确认删除“{0}”？", [todo.title]) : todo.title}</p>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        const path = `${root}/${encodeURIComponent(todo.id)}`;
        if (operation === "delete") {
          await action.mutation.run(path, {method: "DELETE", revision: todo.revision});
          dialog.close(() => {
            onClose();
            onDeleted();
            toast.success(uiText("待办已删除。"));
          });
          return;
        }
        if (operation === "transfer") {
          if (owner.id === todo.owner.id) {
            throw new Error(uiText("请选择另一位负责人。"));
          }
          await action.mutation.run(`${path}/transfer`, {
            method: "POST",
            revision: todo.revision,
            body: {ownerUserId: owner.id, reason}
          });
        } else {
          await action.mutation.run(`${path}/status`, {
            method: "PATCH",
            revision: todo.revision,
            body: {status: states[operation], reason}
          });
        }
        dialog.close(() => {
          onClose();
          onChanged(operation === "transfer" ? uiText("已转交给{0}。", [owner.name]) : operation === "cancel" ? uiText("待办已取消。") : uiText("待办已重新打开。"));
        });
      }, "");
    }}>
      {operation === "transfer" && <div className={ui.field}><span>{uiText("负责人")}</span>
        <div className={ui.actions}><span>{owner.name}</span><Button className={ui.button} type="button"
                                                                     disabled={action.busy}
                                                                     onClick={() => setPicker(true)}>{uiText("选择负责人")}</Button>
        </div>
      </div>}
      {operation !== "delete" &&
        <label className={ui.field}><span>{uiText("操作说明（可选）")}</span><Textarea className={ui.textarea}
                                                                                     maxLength={500} rows={3}
                                                                                     disabled={action.busy}
                                                                                     value={reason}
                                                                                     onChange={(event) => setReason(event.target.value)}/></label>}
      <MutationFeedback action={action} showFieldErrors onReload={() => {
        dialog.close(() => {
          onClose();
          onChanged();
        });
      }}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                         disabled={action.busy}>{uiText("返回")}</DialogCancel><Button
        className={operation === "delete" ? ui.danger : ui.primary}
        disabled={action.busy}>{action.busy ? uiText("正在处理…") : uiText("确认")}</Button></DialogActions>
    </DialogForm>
    {picker && <ApiOptionPicker title={uiText("选择负责人")} path={`${root}/assignees?${params}`} selected={owner.id}
                                onClose={() => setPicker(false)} onSelect={(value) => {
      if (value) {
        setOwner(value);
      }
      setPicker(false);
    }}/>}
    {discard &&
      <Dialog variant="discard" title={uiText("放弃未提交的操作？")} onClose={() => setDiscard(false)}><DialogActions
        className={ui.footer}><DialogCancel className={ui.button}>{uiText("继续填写")}</DialogCancel><DialogCancel
        className={ui.danger} onClick={() => dialog.close(onClose)}>{uiText("放弃修改")}</DialogCancel></DialogActions></Dialog>}
  </Dialog>;
}
