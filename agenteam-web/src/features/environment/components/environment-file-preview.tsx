"use client";

import {useEffect, useState} from "react";
import {Dialog} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {QueryState} from "@/components/ui/query-state";
import {IconCopy, IconDownload, IconFileText, IconImage} from "@/components/ui/icons";
import {apiRequest, errorMessage} from "@/lib/http/api-client";
import {useT} from "@/lib/i18n/locale-provider";
import type {EnvironmentFile, EnvironmentPreview} from "../types/environment";
import {formatFileSize, fileKind} from "../lib/environment-file-format";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-files.module.css";

export function EnvironmentFilePreview({base, file, onClose}: {base: string; file: EnvironmentFile; onClose: () => void}) {
  const t = useT();
  const [preview, setPreview] = useState<EnvironmentPreview | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [copied, setCopied] = useState(false);
  const [imageError, setImageError] = useState(false);
  const image = fileKind(file.name) === "image";
  const url = base + "/files/content?" + new URLSearchParams({path: file.path});
  useEffect(() => {
    if (image) {
      return;
    }
    const controller = new AbortController();
    void apiRequest<EnvironmentPreview>(base + "/files/preview?" + new URLSearchParams({path: file.path}), {signal: controller.signal})
      .then(value => {
        setPreview(value);
        setError("");
      }).catch(failure => {
        if (!controller.signal.aborted) {
          setError(errorMessage(failure));
        }
      });
    return () => controller.abort();
  }, [base, file.path, attempt, image]);

  return <Dialog title={file.name} icon={image ? <IconImage size={22}/> : <IconFileText size={22}/>} size="large" onClose={onClose}
    headerDetails={<p className={styles.previewPath}>{file.path}<span>{formatFileSize(file.size)}</span></p>}
    footer={<>
      {preview && !preview.binary && <Button className={ui.button} onClick={async () => {
        try {
          await navigator.clipboard.writeText(preview.content);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          setError(t("无法复制，请选中文字后使用浏览器的复制操作。"));
        }
      }}><IconCopy size={16}/>{t(copied ? "已复制" : "复制内容")}</Button>}
      <a className={ui.button} href={url} download={file.name}><IconDownload size={16}/>{t("下载文件")}</a>
    </>}>
    {image ? <div className={styles.imagePreview}>
      {imageError ? <p role="alert">{t("图片暂时无法预览，可下载后查看。")}</p> : <>
        {/* 私有图片直接从同源且已校验当前用户的文件接口读取。 */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={url} alt={file.name} onError={() => setImageError(true)}/>
      </>}
    </div> : <QueryState loading={!preview && !error} error={error} empty="" hasData={preview !== null} retry={() => setAttempt(value => value + 1)}>
      {preview?.binary ? <div className={styles.empty}><IconFileText size={32}/><p>{t("此文件暂不支持文本预览，可以下载后打开。")}</p></div>
        : preview?.content ? <div className={styles.codePreview} tabIndex={0} aria-label={t("文件内容")}>
          <pre aria-hidden="true" className={styles.lineNumbers}>{preview.content.split("\n").map((_, i) => i + 1).join("\n")}</pre>
          <pre>{preview.content}</pre>
        </div> : <p className={styles.empty}>{t("此文件为空")}</p>}
      {preview?.truncated && <p className={styles.previewHint}>{t("仅预览部分内容，下载可查看完整文件。")}</p>}
    </QueryState>}
  </Dialog>;
}
