"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {ConversationFile} from "../types/conversation-files";
import type {FilePreviewRendererProps} from "../types/file-preview";
import styles from "./conversation-files.module.css";

/** 预览使用原附件的访问权限，下载入口仍保留原始文档。 */
export function OfficeFilePreview({file, url}: FilePreviewRendererProps) {
  const uiText = useT();
  // 覆盖服务端等待同一预览的 200 秒和转换的 180 秒；关闭面板仍会取消请求。
  const preview = useApiQuery<ConversationFile>(url + "/preview", file.revision, 0, false, {timeoutMs: 400_000});
  if (preview.error) {
    return <div className={styles.error} role="alert">
      <p>{localizeUiMessage(preview.error ?? "", uiText)}</p>
      <Button type="button" onClick={preview.retry}>{uiText("重新生成预览")}</Button>
    </div>;
  }
  if (preview.loading || !preview.data) {
    return <p className={styles.empty} role="status">{uiText("正在生成文档预览…")}</p>;
  }
  return <iframe className={styles.pdfPreview}
                 src={url + "/preview/content?revision=" + encodeURIComponent(preview.data.revision)}
                 title={file.name + uiText("预览")}/>;
}
