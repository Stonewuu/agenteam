"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {Dialog, DialogActions} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {downloadFile} from "@/features/file/api/file-api";
import type {Citation} from "@/features/agent/types/execution";
import ui from "@/components/ui/surface.module.css";
import styles from "./knowledge.module.css";

export function CitationCard({enterpriseId, citation}: { enterpriseId: string; citation: Citation }) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  return <>
    <Button type="button" className={styles.citation} onClick={() => setOpen(true)}
            aria-label={uiText("查看“{0}”的引用原文", [citation.name])}>
      <strong>{citation.name}</strong><span>{location(citation, uiText)}</span><q>{citation.excerpt}</q><span
      className={styles.open}>{uiText("查看原文")}</span>
    </Button>
    {open && <CitationPreview enterpriseId={enterpriseId} chunkId={citation.chunkId} onClose={() => setOpen(false)}/>}
  </>;
}

function CitationPreview({enterpriseId, chunkId, onClose}: {
  enterpriseId: string;
  chunkId: string;
  onClose: () => void
}) {
  const uiText = useT();
  const original = useApiQuery<Citation>(organizationPath(enterpriseId, `/knowledge/citations/${encodeURIComponent(chunkId)}`));
  const action = useFormAction();
  return <Dialog title={uiText("引用原文")} onClose={onClose} drawer><QueryState {...original}
                                                                                 hasData={Boolean(original.data)}
                                                                                 empty={uiText("此引用已经无法读取。")}>
    {original.data && <div className={ui.form}>
      <div><strong>{original.data.name}</strong><p className={ui.description}>{location(original.data, uiText)}</p>
      </div>
      <blockquote className={styles.original}>{original.data.excerpt}</blockquote>
      <DialogActions className={ui.footer}><Button type="button" className={ui.button} disabled={action.busy}
                                                   onClick={() => void action.execute(() => downloadFile(enterpriseId, original.data!.fileId), "")}>{action.busy ? uiText("正在下载…") : uiText("下载文档")}</Button></DialogActions>
      {action.error && <p className={ui.error} role="alert">{localizeUiMessage(action.error ?? "", uiText)}</p>}
    </div>}
  </QueryState></Dialog>;
}

function location(citation: Citation, text: ReturnType<typeof useT>) {
  return citation.page !== null ? text("第 {0} 页", [citation.page]) : citation.section ?? "";
}
