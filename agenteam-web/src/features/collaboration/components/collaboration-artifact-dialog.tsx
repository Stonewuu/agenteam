"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {IconDownload} from "@/components/ui/icons";
import {MarkdownContent} from "@/components/ui/markdown-content";
import {QueryState} from "@/components/ui/query-state";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {downloadFile} from "@/features/file/api/file-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {CollaborationArtifact} from "../types/collaboration";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration.module.css";

type Content = {text?: string; fileId?: string; offset?: number; nextOffset?: number; hasMore?: boolean};

export function CollaborationArtifactDialog({enterpriseId, path, artifact, onClose}: {enterpriseId: string; path: string; artifact: CollaborationArtifact; onClose: () => void}) {
  const t = useT();
  const action = useFormAction();
  const [offset, setOffset] = useState(0);
  const [previous, setPrevious] = useState<number[]>([]);
  const result = useApiQuery<Content>(artifact.summaryOnly ? null : `${path}/artifacts/${artifact.id}?version=${artifact.version}&offset=${offset}&limit=8000`);
  const showSummary = !artifact.summaryOnly && artifact.summary.trim() !== result.data?.text?.trim();
  return <Dialog title={artifact.title} icon={<IconDownload size={22}/>} onClose={onClose} wide>
    {showSummary && <MarkdownContent className={styles.summary} content={artifact.summary} allowImages={false}/>}
    <QueryState {...result} hasData={artifact.summaryOnly || Boolean(result.data)} empty={t("成果暂时无法读取。")}>
      {result.data?.fileId ? <Button className={ui.primary} disabled={action.busy} onClick={() => void action.execute(() => downloadFile(enterpriseId, result.data!.fileId!), "")}><IconDownload size={16}/>{t("下载完整文件")}</Button>
        : <div className={styles.artifact}>{artifact.kind === "text" || artifact.summaryOnly ? <MarkdownContent content={artifact.summaryOnly ? artifact.summary : result.data?.text ?? ""}/> : <pre className={styles.text}>{result.data?.text}</pre>}</div>}
      <div className={styles.actions}>{previous.length > 0 && <Button className={ui.button} onClick={() => {
        setOffset(previous[previous.length - 1]);
        setPrevious(previous.slice(0, -1));
      }}>{t("上一部分")}</Button>}{result.data?.hasMore && <Button className={ui.button} onClick={() => {
        setPrevious([...previous, offset]);
        setOffset(result.data!.nextOffset!);
      }}>{t("继续阅读")}</Button>}</div>
    </QueryState><MutationFeedback action={action}/><DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
  </Dialog>;
}
