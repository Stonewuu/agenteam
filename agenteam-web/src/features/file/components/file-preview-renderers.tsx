"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ComponentType, useState} from "react";
import type {ConversationFile} from "../types/conversation-files";
import type {FilePreviewRenderer, FilePreviewRendererProps} from "../types/file-preview";
import {type FilePreviewFormat, filePreviewFormats} from "../lib/file-preview-formats";
import {HtmlFilePreview, MarkdownFilePreview} from "./document-file-preview";
import {TextFilePreview} from "./text-file-preview";
import {OfficeFilePreview} from "./office-file-preview";
import styles from "./conversation-files.module.css";

export type {FilePreviewRenderer} from "../types/file-preview";

function ImagePreview({file, url}: FilePreviewRendererProps) {
  const uiText = useT();
  const [failed, setFailed] = useState(false);
  return failed ? <p className={styles.empty} role="alert">{uiText("图片暂时无法预览，可下载后查看。")}</p>
    : <div className={styles.imagePreview}>
      {/* 私有文件需要当前会话的认证信息，直接读取已校验权限的图片地址。 */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={url + "/content"} alt={file.name} onError={() => setFailed(true)} decoding="async"/>
    </div>;
}

function MediaPreview({file, url}: FilePreviewRendererProps) {
  const uiText = useT();
  const [failed, setFailed] = useState(false);
  return <div className={styles.mediaPreview}>
    {file.previewKind === "video" ?
      <video src={url + "/content"} controls preload="metadata" aria-label={file.name} onError={() => setFailed(true)}/>
      : <audio src={url + "/content"} controls preload="metadata" aria-label={file.name}
               onError={() => setFailed(true)}/>}
    {failed && <p className={styles.empty} role="alert">{uiText("浏览器暂时无法播放此文件，可下载后查看。")}</p>}
  </div>;
}

function PdfPreview({file, url}: FilePreviewRendererProps) {
  return <iframe className={styles.pdfPreview} src={url + "/content"} title={file.name}/>;
}

const components: Record<FilePreviewFormat, ComponentType<FilePreviewRendererProps>> = {
  html: HtmlFilePreview,
  markdown: MarkdownFilePreview,
  image: ImagePreview,
  video: MediaPreview,
  audio: MediaPreview,
  pdf: PdfPreview,
  office: OfficeFilePreview,
  text: TextFilePreview,
};

/** 新格式在识别表中登记，再提供对应组件；列表、详情与独立标签共用这些定义。 */
export const filePreviewRenderers: readonly FilePreviewRenderer[] = filePreviewFormats.map((format) => ({
  ...format,
  component: components[format.id]
}));

export function findFilePreviewRenderer(file: ConversationFile, renderers = filePreviewRenderers) {
  return file.directory ? undefined : renderers.find((renderer) => renderer.matches(file));
}
