"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconArrowLeft, IconDownload, IconEye, IconRefresh} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {ConversationFile, ConversationFileTarget} from "../types/conversation-files";
import {conversationFilePath} from "../api/conversation-files";
import {formatFileSize} from "../lib/file-size";
import {type FilePreviewRenderer, filePreviewRenderers, findFilePreviewRenderer} from "./file-preview-renderers";
import {TextFilePreview} from "./text-file-preview";
import styles from "./conversation-files.module.css";

export function ConversationFilePreview({
                                          enterprise,
                                          conversation,
                                          file,
                                          onBack,
                                          onPreview,
                                          mode = "source",
                                          onModeChange,
                                          renderers = filePreviewRenderers,
                                          refreshKey = ""
                                        }: {
  enterprise: string;
  conversation: string;
  file: ConversationFileTarget;
  onBack?: () => void;
  onPreview?: (file: ConversationFile) => void;
  mode?: "source" | "preview";
  onModeChange?: (mode: "source" | "preview") => void;
  renderers?: readonly FilePreviewRenderer[];
  refreshKey?: string;
}) {
  const uiText = useT();
  const [version, setVersion] = useState(0);
  const url = conversationFilePath(enterprise, conversation, file.id);
  const query = useApiQuery<ConversationFile>(url, `${refreshKey}:${version}`);
  const current = query.data ?? file;
  const renderer = query.data ? findFilePreviewRenderer(query.data, renderers) : undefined;
  const Renderer = renderer?.hasTextSource && mode === "source" ? TextFilePreview : renderer?.component;
  const modes = onModeChange && renderer?.hasTextSource && renderer.id !== "text";
  return <section className={styles.preview} aria-label={uiText("预览文件 ") + current.name}>
    <div className={styles.previewHeading}>
      {onBack && <Button type="button" className="icon-button" aria-label={uiText("返回文件列表")}
                         onClick={onBack}><IconArrowLeft size={17}/></Button>}
      <div className={styles.previewTitle}><strong
        title={current.name}>{current.name}</strong>{current.sizeBytes !== undefined &&
        <span>{formatFileSize(current.sizeBytes, uiText)}</span>}</div>
      {onPreview && renderer && query.data &&
        <Button type="button" className={styles.openPreview} title={uiText("在新标签页预览")}
                onClick={() => onPreview(query.data!)}><IconEye size={17}/>{uiText("预览")}</Button>}
      <Button type="button" className="icon-button" aria-label={uiText("重新加载文件")}
              onClick={() => setVersion((value) => value + 1)}><IconRefresh size={17}/></Button>
      <a className="icon-button" href={url + "/content?download=true"} download aria-label={uiText("下载文件")}
         title={uiText("下载文件")}><IconDownload size={18}/></a>
    </div>
    {current.source === "workspace" && <p className={styles.filePath} title={current.path}>{current.path}</p>}
    {modes && <div className={styles.viewModes} role="group" aria-label={uiText("文件显示方式")}>
      <Button type="button" aria-pressed={mode === "preview"}
              onClick={() => onModeChange("preview")}>{uiText("预览")}</Button>
      <Button type="button" aria-pressed={mode === "source"}
              onClick={() => onModeChange("source")}>{renderer.id === "html" ? uiText("源码") : uiText("原文")}</Button>
    </div>}
    <div className={styles.previewBody}>
      {query.error ?
        <div className={styles.error} role="alert">{localizeUiMessage(query.error ?? "", uiText)}<Button type="button"
                                                                                                         onClick={query.retry}>{uiText("重新加载")}</Button>
        </div>
        : query.loading || !query.data ? <p className={styles.empty} role="status">{uiText("正在读取文件…")}</p>
          : Renderer ?
            <Renderer key={url + ":" + query.data.revision + ":" + version + ":" + mode} file={query.data} url={url}/>
            : <p className={styles.empty}>{uiText("此格式暂不支持在线预览，可下载后查看。")}</p>}
    </div>
  </section>;
}
