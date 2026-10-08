"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {FileUploadPicker} from "@/features/file/components/file-upload-picker";
import type {DataCollection, DataField, DataImportPreview} from "../types/data";
import {DataFieldEditor} from "./data-field-editor";
import {DataTable} from "./data-table";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import ui from "@/components/ui/surface.module.css";

export function DataImportDialog({enterpriseId, resourceId, target, onClose, onSaved}: {
  enterpriseId: string; resourceId: string; target: DataCollection | null; onClose: () => void; onSaved: () => void;
}) {
  const uiText = useT();
  const uploads = useFileUploads(enterpriseId, "data_import", target?.id ?? "new", resourceId);
  const action = useFormAction();
  const [name, setName] = useState(target?.name ?? "");
  const [preview, setPreview] = useState<DataImportPreview | null>(null);
  const [fields, setFields] = useState<DataField[]>([]);
  const path = organizationPath(enterpriseId, `/data/${encodeURIComponent(resourceId)}`);
  const closing = useConfirmClose(name !== (target?.name ?? "") || Boolean(preview), action.busy, onClose);
  return <><Dialog title={target ? uiText("更新集合文件") : uiText("导入文件")} onClose={onClose}
                   dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}
                   wide={Boolean(preview)}><DialogForm className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    if (!uploads.ready || !name.trim()) {
      return;
    }
    void action.execute(async () => {
      if (!preview) {
        const value = await action.mutation.run<DataImportPreview>(`${path}/import-preview`, {
          method: "POST",
          body: {fileId: uploads.fileIds[0], name: name.trim(), collectionId: target?.id ?? null}
        });
        setFields(value.fields);
        setPreview(value);
      } else {
        await action.mutation.run<DataCollection>(`${path}/import`, {
          method: "POST",
          body: {previewToken: preview.previewToken, name: name.trim(), fields}
        });
        closing.finish(() => {
          uploads.clear();
          onSaved();
        });
      }
    }, "");
  }}><label className={ui.field}><span>{uiText("集合名称")}</span><Input className={ui.input} required maxLength={80}
                                                                         value={name} disabled={action.busy}
                                                                         onChange={(event) => setName(event.target.value)}/></label>
    {!preview ? <><p
        className={ui.description}>{uiText("CSV 表格需使用 UTF-8 编码，第一行为字段名称；不超过 10 兆字节、十万行或一百列。")}</p>
        <FileUploadPicker uploads={uploads} purpose="data_import" disabled={action.busy}/></>
      : <><p
        className={ui.description}>{uiText("共 ")}{preview.rowCount}{uiText(" 行，以下预览前 ")}{preview.rows.length}{uiText(" 行。请确认字段类型和读取范围。")}</p>
        <DataTable fields={preview.fields} rows={preview.rows}/>
        <DataFieldEditor fields={fields} onChange={setFields} editableNames={false} disabled={action.busy}/></>}
    <MutationFeedback action={action}/>
    {action.details.row != null && <p
      className={ui.error}>{uiText("第 ")}{String(action.details.row)}{uiText(" 行，第 ")}{String(action.details.column)}{uiText(" 列：")}{String(action.details.field ?? "")}</p>}
    <DialogActions className={ui.footer}>{preview &&
      <Button type="button" className={ui.button} disabled={action.busy} onClick={() => {
        setPreview(null);
        action.resetFeedback();
      }}>{uiText("重新预览")}</Button>}
      <DialogCancel type="button" className={ui.button} disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
        className={ui.primary}
        disabled={action.busy || !uploads.ready || !name.trim()}>{action.busy ? uiText("正在处理…") : preview ? uiText("确认导入") : uiText("预览文件")}</Button></DialogActions>
  </DialogForm></Dialog>{closing.confirmation}</>;
}
