"use client";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {IconFolder, IconTrash} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {apiRequest} from "@/lib/http/api-client";
import {useT} from "@/lib/i18n/locale-provider";
import type {EnvironmentFile} from "../types/environment";
import type {UploadSource} from "../lib/environment-upload-selection";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-files.module.css";

export type FileOperation = {kind: "folder"; path: string} | {kind: "upload"; path: string; sources?: UploadSource[]} | {kind: "delete"; file: EnvironmentFile};

export function EnvironmentFileDialog({base, operation, onClose, onChanged}: {
  base: string; operation: Exclude<FileOperation, {kind: "upload"}>; onClose: () => void; onChanged: () => void;
}) {
  const t = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const [name, setName] = useState("");
  const deleting = operation.kind === "delete";
  const title = operation.kind === "folder" ? t("新建文件夹") : t(operation.file.directory ? "删除文件夹" : "删除文件");
  const path = deleting ? operation.file.path : operation.path;

  function submit() {
    void action.execute(async () => {
      if (operation.kind === "folder") {
        await apiRequest(base + "/files/directories", {method: "POST", body: {path, name: name.trim()}});
      } else {
        await apiRequest(base + "/files?" + new URLSearchParams({path, directory: String(operation.file.directory),
          modifiedAt: operation.file.modifiedAt}), {method: "DELETE"});
      }
      onChanged();
      dialog.close();
    }, t(deleting ? "已删除" : "文件夹已创建"));
  }

  return <Dialog title={title} size="small" busy={action.busy} onClose={onClose} dialogRef={dialog.ref}
    icon={deleting ? <IconTrash size={20}/> : <IconFolder size={20}/>}>
    <DialogForm className={styles.operationForm} onSubmit={event => {
      event.preventDefault();
      submit();
    }}>
      <div className={styles.targetPath}><IconFolder size={16}/><span>{path}</span></div>
      {operation.kind === "folder" && <Field label={t("文件夹名称")} required error={action.fieldErrors.name?.join(" ")}>
        <Input name="name" autoFocus required maxLength={150} autoComplete="off" disabled={action.busy} value={name}
          onChange={event => {
            setName(event.target.value);
            action.clearFieldError("name");
          }}/>
      </Field>}
      {deleting && <p className={styles.warning}>{t(operation.file.directory
        ? "此文件夹及其中的所有文件将永久删除。" : "此文件将永久删除。")}</p>}
      {action.error && !action.fieldErrors.name && <p className={ui.error} role="alert">{action.error}</p>}
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button type="submit" className={deleting ? ui.danger : ui.primary}
          disabled={action.busy || (operation.kind === "folder" && !name.trim())}>
          {action.busy ? t("正在处理…") : deleting ? t("删除") : t("创建")}
        </Button></DialogActions>
    </DialogForm>
  </Dialog>;
}
