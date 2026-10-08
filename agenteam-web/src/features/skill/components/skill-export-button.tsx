"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {type FileDownload, saveDownload} from "@/features/file/api/file-api";
import ui from "@/components/ui/surface.module.css";

export function SkillExportButton({enterpriseId, resourceId, versionId}: {
  enterpriseId: string;
  resourceId: string;
  versionId: string
}) {
  const uiText = useT();
  const action = useFormAction();
  return <div><Button className={ui.button} type="button" disabled={action.busy}
                      onClick={() => void action.execute(async () => {
                        const download = await action.mutation.run<FileDownload>(organizationPath(enterpriseId, `/skills/${encodeURIComponent(resourceId)}/versions/${encodeURIComponent(versionId)}/export`), {method: "POST"});
                        await saveDownload(download);
                      }, "")}>{action.busy ? uiText("正在导出…") : uiText("导出技能")}</Button>{action.error &&
    <p role="alert" className={ui.error}>{localizeUiMessage(action.error ?? "", uiText)}</p>}</div>;
}
