"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import type {Memory} from "../types/memory";
import ui from "@/components/ui/surface.module.css";

export function MemoryDeleteDialog({enterpriseId, agentId, agentName, memory, onClose, onDeleted, onReload}: {
  enterpriseId: string;
  agentId: string;
  agentName: string;
  memory: Memory | null;
  onClose: () => void;
  onDeleted: () => void;
  onReload: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  useBlockNavigation(false, action.busy);
  return <Dialog title={memory ? uiText("删除偏好") : uiText("清空偏好")} busy={action.busy} onClose={onClose}
                 dialogRef={dialog.ref}>
    <p
      className={ui.description}>{memory ? uiText("删除“{0}”的偏好内容？", [memory.memoryKey]) : uiText("清空为“{0}”保存的全部偏好？", [agentName])}{uiText("删除后无法恢复。")}</p>
    <MutationFeedback action={action} onReload={() => dialog.close(onReload)}/>
    <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                       disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
      className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/agents/${encodeURIComponent(agentId)}/memories${memory ? `/${encodeURIComponent(memory.id)}` : ""}`), {
        method: "DELETE",
        revision: memory?.revision
      });
      dialog.close(onDeleted);
    }, "")}>{action.busy ? uiText("正在删除…") : memory ? uiText("删除") : uiText("清空")}</Button></DialogActions>
  </Dialog>;
}
