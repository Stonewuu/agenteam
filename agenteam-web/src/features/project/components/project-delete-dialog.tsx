"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {Dialog, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {IconFolder, IconTrash} from "@/components/ui/icons";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useT} from "@/lib/i18n/locale-provider";
import type {WorkspaceProject} from "../types/project";
import ui from "@/components/ui/surface.module.css";
import styles from "./project-picker.module.css";

export function ProjectDeleteDialog({project, base, onClose, onDeleted}: {
  project: WorkspaceProject; base: string; onClose: () => void; onDeleted: () => void;
}) {
  const t = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const [deleteFiles, setDeleteFiles] = useState(false);

  function remove() {
    void action.execute(async () => {
      await action.mutation.run(base + "/" + encodeURIComponent(project.id), {method: "DELETE", body: {deleteFiles}});
      dialog.close(onDeleted);
    }, t("项目已删除"));
  }

  return <Dialog title={t("删除项目")} icon={<IconTrash size={20}/>} size="small" dialogRef={dialog.ref} busy={action.busy} onClose={onClose}
    footer={<><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
      <Button className={ui.danger} disabled={action.busy} onClick={remove}>{t(action.busy ? "正在删除…" : "删除项目")}</Button></>}>
    <div className={styles.deleteBody}>
      <div className={styles.target}><IconFolder size={21}/><div><strong>{project.name}</strong><small>{project.directory}</small></div></div>
      <p>{t("关联的对话记录会保留。")}</p>
      <label className={styles.deleteChoice}><Checkbox checked={deleteFiles} disabled={action.busy} onCheckedChange={setDeleteFiles}/>
        <span><strong>{t("同时删除目录中的所有文件")}</strong>
          <small aria-live="polite">{t(deleteFiles ? "项目目录及其中的文件将永久删除。" : "文件将保留在“我的环境”中。")}</small></span>
      </label>
    </div>
  </Dialog>;
}
