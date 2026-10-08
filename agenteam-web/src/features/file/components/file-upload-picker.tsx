"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {type RefObject, useRef} from "react";
import {fileAccept, type FilePurpose} from "../api/file-api";
import {formatFileSize} from "../lib/file-size";
import type {useFileUploads} from "../hooks/use-file-uploads";
import ui from "@/components/ui/surface.module.css";
import styles from "./file-upload.module.css";
import {IconPaperclip, IconX} from "@/components/ui/icons";
import {fileStatusText} from "../lib/file-status";

export function FileUploadPicker({
                                   uploads,
                                   purpose,
                                   disabled = false,
                                   allowNew = true,
                                   compact = false,
                                   iconOnly = false,
                                   showFiles = true,
                                   multiple = purpose === "knowledge" || purpose === "attachment",
                                   label: customLabel,
                                   inputRef
                                 }: {
  uploads: ReturnType<typeof useFileUploads>;
  purpose: FilePurpose;
  disabled?: boolean;
  allowNew?: boolean;
  compact?: boolean;
  iconOnly?: boolean;
  showFiles?: boolean;
  multiple?: boolean;
  label?: string;
  inputRef?: RefObject<HTMLInputElement | null>;
}) {
  const uiText = useT();
  const label = customLabel ?? uiText("选择文件");
  const localInput = useRef<HTMLInputElement>(null);
  const input = inputRef ?? localInput;
  return <div className={`${styles.picker} ${compact ? styles.compact : ""}`}>
    <Input ref={input} type="file" hidden disabled={disabled || !allowNew} accept={fileAccept[purpose]}
           multiple={multiple} onChange={(event) => {
      if (!disabled && allowNew) {
        uploads.add(Array.from(event.target.files ?? []));
      }
      event.target.value = "";
    }}/>
    {allowNew &&
      <Button type="button" className={iconOnly ? "icon-button" : ui.button} aria-label={label} disabled={disabled}
              onClick={() => input.current?.click()}>{iconOnly ? <IconPaperclip size={19}/> : label}</Button>}
    {showFiles && <FileUploadSelection uploads={uploads} disabled={disabled}/>}
  </div>;
}

export function FileUploadSelection({uploads, disabled = false}: {
  uploads: ReturnType<typeof useFileUploads>;
  disabled?: boolean
}) {
  const uiText = useT();
  return <>
    {uploads.error && <p className={ui.error} role="alert">{localizeUiMessage(uploads.error ?? "", uiText)}</p>}
    {uploads.items.length > 0 &&
      <ul className={styles.files} aria-label={uiText("已选择的文件")}>{uploads.items.map((item) => <li
        key={item.localId}>
        <div className={styles.name}>
          <strong>{item.file.name}</strong><span>{formatFileSize(item.file.size, uiText)} · {item.error ? uiText("未能完成") : item.reference ? uiText(fileStatusText(item.reference)) : item.started ? uiText("正在上传…") : uiText("等待上传")}</span>
          {item.error && <p role="alert">{localizeUiMessage(item.error ?? "", uiText)}</p>}</div>
        {item.error && item.reference?.status !== "rejected" &&
          <Button type="button" className={ui.button} disabled={disabled || item.busy}
                  onClick={() => uploads.retry(item.localId)}>{uiText("重试")}</Button>}
        <Button type="button" className={styles.remove} disabled={disabled}
                aria-label={uiText("移除文件“{0}”", [item.file.name])}
                onClick={() => uploads.remove(item.localId)}><IconX size={17}/></Button>
      </li>)}</ul>}
  </>;
}
