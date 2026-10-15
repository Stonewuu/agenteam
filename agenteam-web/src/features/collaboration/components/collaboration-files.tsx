"use client";

import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {DetailSection} from "@/components/ui/detail-section";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {IconDownload, IconEye, IconFileText} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {downloadFile} from "@/features/file/api/file-api";
import {ConversationFilePreview} from "@/features/file/components/conversation-file-preview";
import {useConversationFiles} from "@/features/file/hooks/use-conversation-files";
import {formatFileSize} from "@/features/file/lib/file-size";
import type {ConversationFile} from "@/features/file/types/conversation-files";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration.module.css";

/** 只展示岗位对话实际生成的附件，不从模型摘要中的路径或编号猜测文件。 */
export function CollaborationFiles({enterpriseId, conversationId, roleName, refreshKey}: {
  enterpriseId: string; conversationId: string; roleName: string; refreshKey: string;
}) {
  const t = useT();
  const query = useConversationFiles(enterpriseId, conversationId, "context", "", true, false, refreshKey);
  const files = query.items.filter((file) => file.source === "generated" && !file.directory && file.id.startsWith("f."));
  const action = useFormAction();
  const [preview, setPreview] = useState<ConversationFile | null>(null);
  return <AnimatedHeight preserveControlShadows>
    {(query.initialLoading || query.error || files.length > 0 || query.hasMore) && <DetailSection className={styles.section}
      title={t("{0}生成的文件", [roleName])} icon={<IconDownload size={19}/>}>
      <QueryState loading={query.initialLoading} error={query.error} retry={query.reload} hasData={files.length > 0}
        empty={t("暂无可下载文件。") }>
        {files.map((file) => <article className={styles.artifactRow} key={file.id}>
          <span className={styles.artifactIcon} data-icon-surface><IconFileText size={20}/></span>
          <div className={styles.artifactCopy}><div className={styles.heading}><h3>{file.name}</h3>
            <span className={styles.muted}>{formatFileSize(file.sizeBytes, t)}</span></div>
            <div className={styles.actions}>
              <Button className={ui.primary} disabled={action.busy} onClick={() => void action.execute(() => downloadFile(enterpriseId, file.id.slice(2)), "")}><IconDownload size={16}/>{t("下载文件")}</Button>
              <Button className={ui.button} onClick={() => setPreview(file)}><IconEye size={16}/>{t("预览")}</Button>
            </div>
          </div>
        </article>)}
      </QueryState>
      {query.hasMore && <Button className={ui.button} disabled={query.loadingMore || query.loading} onClick={() => void query.loadMore()}>{t("加载更多文件")}</Button>}
      <MutationFeedback action={action}/>
    </DetailSection>}
    {preview && <Dialog title={preview.name} icon={<IconFileText size={21}/>} onClose={() => setPreview(null)} wide>
      <ConversationFilePreview enterprise={enterpriseId} conversation={conversationId} file={preview}/>
      <DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
    </Dialog>}
  </AnimatedHeight>;
}
