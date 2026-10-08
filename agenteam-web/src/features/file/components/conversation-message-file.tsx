"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {IconDownload, IconFileText} from "@/components/ui/icons";
import type {FileReference} from "@/features/agent/types/execution";
import type {ConversationFileTarget} from "../types/conversation-files";
import {conversationFilePath} from "../api/conversation-files";
import {formatFileSize} from "../lib/file-size";
import {useConversationFileViewer} from "./conversation-file-context";
import {FileDownloadButton} from "./file-download-button";
import styles from "./conversation-message-file.module.css";

export function ConversationFileLink({file, available = true}: { file: ConversationFileTarget; available?: boolean }) {
  const uiText = useT();
  const viewer = useConversationFileViewer();
  return viewer?.conversation && viewer.openFile && available
    ? <Button type="button" className={styles.link} title={file.path} aria-label={uiText("在侧栏打开 ") + file.name}
              onClick={() => viewer.openFile?.(file)}><IconFileText size={16}/><span>{file.name}</span></Button>
    : <span className={styles.name} title={file.path}>{file.name}</span>;
}

export function ConversationFileImage({file, width, height, revision}: {
  file: ConversationFileTarget;
  width?: number;
  height?: number;
  revision?: string | null
}) {
  const uiText = useT();
  const viewer = useConversationFileViewer();
  const [retry, setRetry] = useState(0);
  const [failedSource, setFailedSource] = useState<string | null>(null);
  if (!viewer?.conversation) {
    return null;
  }
  const url = conversationFilePath(viewer.enterprise, viewer.conversation, file.id) + "/content?" + new URLSearchParams({
    revision: revision ?? "",
    retry: String(retry)
  });
  const picture = <>
    {/* 私有图片直接使用当前会话的文件接口，避免外部图片代理读取受保护内容。 */}
    {/* eslint-disable-next-line @next/next/no-img-element */}
    <img src={url} alt={file.name} width={width} height={height} loading="lazy" decoding="async"
         onError={() => setFailedSource(url)}/>
  </>;
  return <AnimatedHeight preserveControlShadows>
    <div className={styles.image}>
      {failedSource === url ?
        <div className={styles.imageError} role="alert"><span>{uiText("图片暂时无法显示。")}</span><Button type="button"
                                                                                                          onClick={() => setRetry((value) => value + 1)}>{uiText("重新加载")}</Button>
        </div>
        : viewer.openFile ?
          <Button type="button" className={styles.imageButton} aria-label={uiText("在侧栏查看图片 ") + file.name}
                  onClick={() => viewer.openFile?.(file)}>{picture}</Button> : picture}
    </div>
  </AnimatedHeight>;
}

export function ConversationFileAttachment({enterpriseId, file}: { enterpriseId: string; file: FileReference }) {
  const uiText = useT();
  const viewer = useConversationFileViewer();
  if (!viewer?.conversation || !viewer.openFile || file.status !== "ready") {
    return <FileDownloadButton enterpriseId={enterpriseId} file={file}/>;
  }
  const target = {id: "f." + file.id, name: file.name, path: file.name, sizeBytes: file.sizeBytes};
  return <div className={styles.attachment}>
    <ConversationFileLink file={target}/><span className={styles.size}>{formatFileSize(file.sizeBytes, uiText)}</span>
    <a className={styles.download}
       href={conversationFilePath(viewer.enterprise, viewer.conversation, target.id) + "/content?download=true"}
       download aria-label={uiText("下载 ") + file.name} title={uiText("下载文件")}><IconDownload size={17}/></a>
  </div>;
}
