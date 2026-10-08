"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";

import {Select} from "@/components/ui/select";


import {useId, useState} from "react";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import type {EnterpriseContext} from "@/features/auth/types/identity";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {ApiOptionPicker} from "@/features/workspace/components/api-option-picker";
import type {Todo, TodoDraftSource, TodoWrite} from "../types/todo";
import ui from "@/components/ui/surface.module.css";
import styles from "./todo.module.css";

function initialForm(context: EnterpriseContext, initial: Todo | null, source?: TodoDraftSource): TodoWrite {
  return {
    title: initial?.title ?? (source ? Array.from(source.text.trim().split("\n")[0]).slice(0, 200).join("") : ""),
    description: initial?.description ?? source?.text ?? "",
    ownerUserId: initial?.owner.id ?? context.member.userId,
    teamId: initial?.teamId ?? null,
    dueDate: initial?.dueDate ?? null,
    priority: initial?.priority ?? "normal",
    sourceType: initial?.sourceType ?? source?.sourceType ?? "manual",
    sourceConversationId: initial ? null : source?.sourceConversationId ?? null,
    sourceMessageId: initial ? null : source?.sourceMessageId ?? null,
    sourceRunId: initial ? null : source?.sourceRunId ?? null
  };
}

export function TodoEditor({context, initial, source, onClose, onSaved, onReload}: {
  context: EnterpriseContext;
  initial: Todo | null;
  source?: TodoDraftSource;
  onClose: () => void;
  onSaved: (value: Todo) => void;
  onReload: () => void;
}) {
  const uiText = useT();
  const [original] = useState(() => initialForm(context, initial, source));
  const [form, setForm] = useState(original);
  const [ownerName, setOwnerName] = useState(initial?.owner.displayName ?? context.member.displayName);
  const [teamName, setTeamName] = useState(initial?.teamName ?? "");
  const [picker, setPicker] = useState<"owner" | "team" | null>(null);
  const [discard, setDiscard] = useState(false);
  const action = useFormAction();
  const formId = useId();
  const dialog = useDialogControl();
  const dirty = JSON.stringify(form) !== JSON.stringify(original) || Boolean(source);
  useBlockNavigation(dirty, action.busy);
  const root = organizationPath(context.enterprise.id, "/todos");
  const canChooseTeam = context.permissions.includes("todo.team_manage");
  const canPersonal = !initial || [initial.owner.id, initial.createdBy.id].includes(context.member.userId);
  const params = new URLSearchParams();
  if (initial) {
    params.set("todoId", initial.id);
  }
  if (form.teamId) {
    params.set("teamId", form.teamId);
  }
  const update = <K extends keyof TodoWrite>(key: K, value: TodoWrite[K]) => setForm((old) => ({...old, [key]: value}));
  const invalid = (field: string) => Boolean(action.fieldErrors[field]?.length);
  return <Dialog title={initial ? uiText("编辑待办") : source ? uiText("转为待办") : uiText("创建待办")}
                 onClose={onClose} dialogRef={dialog.ref} onRequestClose={() => {
    if (dirty) {
      setDiscard(true);
      return false;
    }
    return true;
  }} busy={action.busy}
                 footer={<><DialogCancel className={ui.button}
                                         disabled={action.busy}>{uiText("取消")}</DialogCancel><Button form={formId}
                                                                                                       type="submit"
                                                                                                       className={ui.primary}
                                                                                                       disabled={action.busy}>{action.busy ? uiText("正在保存…") : initial ? uiText("保存修改") : uiText("创建待办")}</Button></>}>
    <form id={formId} className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        if (Array.from(form.description).length > 5000) {
          throw new Error(uiText("待办内容最多 5000 字，请保留需要处理的事项。"));
        }
        const value = await action.mutation.run<Todo>(`${root}${initial ? `/${encodeURIComponent(initial.id)}` : ""}`, {
          method: initial ? "PUT" : "POST",
          revision: initial?.revision,
          body: form
        });
        dialog.close(() => onSaved(value));
      }, "");
    }}><Fieldset className={`${ui.form} ${styles.fields}`} disabled={action.busy}>
      <label className={ui.field}><span>{uiText("标题")}</span><Input className={ui.input} required maxLength={200}
                                                                      value={form.title} aria-invalid={invalid("title")}
                                                                      onChange={(event) => update("title", event.target.value)}/></label>
      <label className={ui.field}><span>{uiText("待办内容")}</span><Textarea className={ui.textarea} rows={6}
                                                                             maxLength={5000} value={form.description}
                                                                             aria-invalid={invalid("description")}
                                                                             onChange={(event) => update("description", event.target.value)}/>{Array.from(form.description).length > 5000 &&
        <small>{uiText("当前内容超过 5000 字，请删减后保存。")}</small>}</label>
      <div className={ui.field}><span>{uiText("负责人")}</span>
        <div className={styles.choice}><span>{ownerName}</span><Button className={ui.button} type="button"
                                                                       onClick={() => setPicker("owner")}>{uiText("选择负责人")}</Button>
        </div>
      </div>
      {(canChooseTeam || form.teamId) && <div className={ui.field}><span>{uiText("所属团队")}</span>
        <div className={styles.choice}><span>{form.teamId ? teamName : uiText("个人待办")}</span>
          {canChooseTeam ? <Button className={ui.button} type="button"
                                   onClick={() => setPicker("team")}>{uiText("选择团队")}</Button> : canPersonal && form.teamId &&
            <Button className={ui.button} type="button" onClick={() => {
              update("teamId", null);
              setTeamName("");
            }}>{uiText("改为个人待办")}</Button>}</div>
      </div>}
      <div className={ui.columns}><label className={ui.field}><span>{uiText("截止日期（可选）")}</span><Input type="date"
                                                                                                            className={ui.input}
                                                                                                            min="1000-01-01"
                                                                                                            max="9999-12-31"
                                                                                                            value={form.dueDate ?? ""}
                                                                                                            aria-invalid={invalid("dueDate")}
                                                                                                            onChange={(event) => update("dueDate", event.target.value || null)}/></label>
        <label className={ui.field}><span>{uiText("优先级")}</span><Select className={ui.select} value={form.priority}
                                                                           onChange={(event) => update("priority", event.target.value as TodoWrite["priority"])}>
          <option value="normal">{uiText("普通")}</option>
          <option value="high">{uiText("高")}</option>
        </Select></label></div>
      <MutationFeedback action={action} onReload={() => dialog.close(onReload)} showFieldErrors/>
    </Fieldset></form>
    {picker === "owner" &&
      <ApiOptionPicker title={uiText("选择负责人")} path={`${root}/assignees?${params}`} selected={form.ownerUserId}
                       onClose={() => setPicker(null)} onSelect={(value) => {
        if (!value) {
          return;
        }
        update("ownerUserId", value.id);
        setOwnerName(value.name);
        setPicker(null);
      }}/>}
    {picker === "team" &&
      <ApiOptionPicker title={uiText("选择团队")} path={`${root}/teams?purpose=assign`} selected={form.teamId}
                       clearLabel={canPersonal ? uiText("个人待办") : undefined} onClose={() => setPicker(null)}
                       onSelect={(value) => {
                         if (form.teamId !== (value?.id ?? null)) {
                           setForm((old) => ({...old, teamId: value?.id ?? null, ownerUserId: context.member.userId}));
                           setOwnerName(context.member.displayName);
                         }
                         setTeamName(value?.name ?? "");
                         setPicker(null);
                       }}/>}
    {discard &&
      <Dialog variant="discard" title={uiText("放弃未保存的待办？")} onClose={() => setDiscard(false)}><DialogActions
        className={ui.footer}><DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogCancel
        className={ui.danger} onClick={() => dialog.close(onClose)}>{uiText("放弃修改")}</DialogCancel></DialogActions></Dialog>}
  </Dialog>;
}
