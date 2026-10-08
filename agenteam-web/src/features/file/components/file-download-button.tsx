"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useFormAction} from "@/features/auth/hooks/use-form-action";
import type {FileReference} from "@/features/agent/types/execution";
import {downloadFile} from "../api/file-api";
import ui from "@/components/ui/surface.module.css";

export function FileDownloadButton({enterpriseId, file}: { enterpriseId: string; file: FileReference }) {
  const uiText = useT();
  const action = useFormAction();
  const size = file.sizeBytes >= 1_000_000 ? `${(file.sizeBytes / 1_000_000).toFixed(1)} MB` : `${Math.max(1, Math.ceil(file.sizeBytes / 1000))} kB`;
  return <div className={ui.form}>
    <div className={ui.actions}>
      <Button type="button" className={ui.button} disabled={action.busy}
              onClick={() => void action.execute(() => downloadFile(enterpriseId, file.id), "")}>{action.busy ? uiText("正在下载…") : file.name}</Button>
      <span className={ui.description}>{size}</span></div>
    {action.error && <p className={ui.error} role="alert">{localizeUiMessage(action.error ?? "", uiText)}</p>}</div>;
}
