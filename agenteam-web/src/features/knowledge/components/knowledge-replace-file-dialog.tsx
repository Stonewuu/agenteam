"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {FileUploadPicker} from "@/features/file/components/file-upload-picker";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {KnowledgeDocument} from "../types/knowledge";
import ui from "@/components/ui/surface.module.css";

export function KnowledgeReplaceFileDialog({enterpriseId, resourceId, document, onClose, onReplaced}: {
  enterpriseId: string; resourceId: string; document: KnowledgeDocument; onClose: () => void; onReplaced: () => void;
}) {
  const uiText = useT();
  const uploads = useFileUploads(enterpriseId, "knowledge", `replace-document:${document.id}`, resourceId);
  const action = useFormAction();
  const dialog = useDialogControl();
  const ready = uploads.ready && uploads.fileIds.length === 1;
  return <Dialog title={uiText("更新“{0}”", [document.name])} onClose={onClose} dialogRef={dialog.ref}
                 busy={action.busy}><DialogForm className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    if (!ready) {
      return;
    }
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/knowledge/${encodeURIComponent(resourceId)}/documents/${encodeURIComponent(document.id)}/replace-file`),
        {method: "POST", revision: document.revision, body: {fileId: uploads.fileIds[0]}});
      dialog.close(() => {
        uploads.clear();
        onReplaced();
      });
    }, "");
  }}>
    <p className={ui.description}>{uiText("选择一份新的文件。新内容处理完成前，已有内容仍可使用。")}</p>
    <FileUploadPicker uploads={uploads} purpose="knowledge" multiple={false} allowNew={uploads.items.length === 0}
                      disabled={action.busy}/>
    <MutationFeedback action={action} onReload={() => dialog.close(onReplaced)}/>
    <DialogActions className={ui.footer}><DialogCancel type="button" className={ui.button}
                                                       disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button className={ui.primary}
              disabled={!ready || action.busy}>{action.busy ? uiText("正在提交…") : uiText("更新文件")}</Button></DialogActions>
  </DialogForm></Dialog>;
}
