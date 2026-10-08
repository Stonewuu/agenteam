"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {useRouter} from "next/navigation";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import type {ResourceDetail, ResourceSummary} from "../types/resource";
import {resourceLabel, resourcePage} from "../lib/resource-display";
import {TextField} from "./resource-fields";
import ui from "@/components/ui/surface.module.css";

export function ResourceCopyDialog({enterpriseId, resource, onClose}: {
  enterpriseId: string;
  resource: ResourceSummary;
  onClose: () => void
}) {
  const uiText = useT();
  const router = useRouter();
  const initial = uiText("{0} 副本", [resource.name.slice(0, 77)]);
  const [name, setName] = useState(initial);
  const action = useFormAction();
  const closing = useConfirmClose(name !== initial, action.busy, onClose);
  return <Dialog title={uiText("复制{0}“{1}”", [resourceLabel(resource.kind), resource.name])} onClose={onClose}
                 dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}><DialogForm
    className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      const created = await action.mutation.run<ResourceDetail>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/copy`), {
        method: "POST",
        body: {name}
      });
      closing.finish(() => {
        onClose();
        router.push(resourcePage(enterpriseId, resource.kind, created.resource.id));
      });
    });
  }}><TextField label={uiText("副本名称")} name="name" value={name} onChange={setName} maximum={80} required
                errors={action.fieldErrors}/>
    <MutationFeedback action={action}/><DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                                                          type="button"
                                                                                          disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
      className={ui.primary}
      disabled={action.busy}>{action.busy ? uiText("正在复制…") : uiText("创建副本")}</Button></DialogActions>
  </DialogForm>{closing.confirmation}</Dialog>;
}
