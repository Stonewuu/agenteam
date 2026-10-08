"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {type ReactNode, useMemo} from "react";
import {MarkdownContent} from "@/components/ui/markdown-content";
import {useFilePreviewDocument} from "../hooks/use-file-preview-document";
import {buildHtmlPreviewDocument, htmlPreviewSandbox} from "../lib/html-preview-document";
import type {FilePreviewRendererProps} from "../types/file-preview";
import styles from "./conversation-files.module.css";

function DocumentStatus({content, error, children}: { content: string | null; error: string; children: ReactNode }) {
  const uiText = useT();
  if (error) {
    return <p className={styles.empty} role="alert">{localizeUiMessage(error ?? "", uiText)}</p>;
  }
  if (content === null) {
    return <p className={styles.empty} role="status">{uiText("正在读取预览内容…")}</p>;
  }
  if (!content.trim()) {
    return <p className={styles.empty}>{uiText("此文件为空。")}</p>;
  }
  return children;
}

export function HtmlFilePreview({file, url}: FilePreviewRendererProps) {
  const uiText = useT();
  const document = useFilePreviewDocument(url, file.sizeBytes);
  const source = useMemo(() => document.content === null ? "" : buildHtmlPreviewDocument(document.content), [document.content]);
  return <DocumentStatus {...document}>
    <iframe className={styles.htmlPreview} title={file.name + uiText(" 网页预览")} srcDoc={source}
            sandbox={htmlPreviewSandbox} referrerPolicy="no-referrer"/>
  </DocumentStatus>;
}

export function MarkdownFilePreview({file, url}: FilePreviewRendererProps) {
  const document = useFilePreviewDocument(url, file.sizeBytes);
  return <DocumentStatus {...document}>
    <div className={styles.markdown}><MarkdownContent content={document.content ?? ""} mode="static" allowImages={false}
                                                      allowRelativeLinks={false}/></div>
  </DocumentStatus>;
}
