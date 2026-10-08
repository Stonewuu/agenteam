"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {useEffect, useState} from "react";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {DataConfig} from "@/features/resource/types/resource";
import type {DataCollection} from "../types/data";
import {DataFieldEditor, emptyField} from "./data-field-editor";
import ui from "@/components/ui/surface.module.css";

export function DataCollectionDialog({enterpriseId, resourceId, sourceType, initial, onClose, onSaved}: {
  enterpriseId: string;
  resourceId: string;
  sourceType: DataConfig["sourceType"];
  initial: DataCollection | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const uiText = useT();
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const closing = useConfirmClose(dirty, busy, onClose);
  return <><Dialog title={initial ? uiText("编辑集合字段") : uiText("添加数据集合")} onClose={onClose}
                   dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={busy} wide><DataCollectionForm
    enterpriseId={enterpriseId} resourceId={resourceId} sourceType={sourceType} initial={initial}
    onClose={closing.close} onSaved={() => closing.finish(onSaved)} onDirty={setDirty}
    onBusy={setBusy}/></Dialog>{closing.confirmation}</>;
}

export function DataCollectionForm({
                                     enterpriseId,
                                     resourceId,
                                     sourceType,
                                     initial,
                                     onClose,
                                     onSaved,
                                     readOnly = false,
                                     onDirty,
                                     onBusy
                                   }: {
  enterpriseId: string;
  resourceId: string;
  sourceType: DataConfig["sourceType"];
  initial: DataCollection | null;
  onClose?: () => void;
  onSaved: () => void;
  readOnly?: boolean;
  onDirty?: (value: boolean) => void;
  onBusy?: (value: boolean) => void;
}) {
  const uiText = useT();
  const [name, setName] = useState(initial?.name ?? "");
  const [sourceName, setSourceName] = useState(initial?.sourceName ?? (sourceType === "http" ? "$" : ""));
  const [fields, setFields] = useState(initial?.fields ?? [emptyField()]);
  const action = useFormAction();
  const dirty = initial ? name !== initial.name || sourceName !== initial.sourceName || JSON.stringify(fields) !== JSON.stringify(initial.fields) : Boolean(name.trim());
  useBlockNavigation(dirty, action.busy);
  useEffect(() => {
    onDirty?.(dirty);
  }, [dirty, onDirty]);
  useEffect(() => {
    onBusy?.(action.busy);
  }, [action.busy, onBusy]);
  return <DialogForm className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/data/${encodeURIComponent(resourceId)}/collections${initial ? `/${encodeURIComponent(initial.id)}` : ""}`),
        {method: initial ? "PUT" : "POST", revision: initial?.revision, body: {name: name.trim(), sourceName, fields}});
      onSaved();
    }, "");
  }}><Fieldset className="plain-fieldset" disabled={action.busy || readOnly}>
    <label className={ui.field}><span>{uiText("集合名称")}</span><Input className={ui.input} required maxLength={80}
                                                                        value={name}
                                                                        onChange={(event) => setName(event.target.value)}/></label>
    {sourceType !== "file" && <label
      className={ui.field}><span>{sourceType === "mysql" ? uiText("表名或视图名") : uiText("记录数组的位置")}</span><Input
      className={ui.input} required maxLength={sourceType === "mysql" ? 64 : 128} readOnly={Boolean(initial)}
      value={sourceName} onChange={(event) => setSourceName(event.target.value)}/>
      {sourceType === "http" && <span
        className={ui.description}>{uiText("根数组填写 $；对象中的数组可填写 /items。嵌套字段可填写 /customer/name。")}</span>}
    </label>}
    <DataFieldEditor fields={fields} onChange={setFields} editableNames={sourceType !== "file"}
                     canAdd={sourceType !== "file"} disabled={action.busy}/>
  </Fieldset><MutationFeedback action={action}/>
    {action.details.row != null && <p
      className={ui.error}>{uiText("第 ")}{String(action.details.row)}{uiText(" 行，第 ")}{String(action.details.column)}{uiText(" 列：")}{String(action.details.field ?? "")}</p>}
    {!readOnly && <DialogActions className={ui.footer}>{onClose &&
      <DialogCancel type="button" className={ui.button} disabled={action.busy}>{uiText("取消")}</DialogCancel>}<Button
      className={ui.primary}
      disabled={action.busy || !name.trim() || (sourceType !== "file" && !sourceName.trim())}>{action.busy ? uiText("正在检查并保存…") : uiText("保存集合")}</Button></DialogActions>}
  </DialogForm>;
}
