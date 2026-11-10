"use client";

import {type ReactNode, type Ref, useImperativeHandle} from "react";
import {Button} from "@/components/ui/button";
import {IconSend} from "@/components/ui/icons";
import {Textarea} from "@/components/ui/textarea";
import {FileUploadSelection} from "@/features/file/components/file-upload-picker";
import type {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {pasteUpload} from "@/features/file/lib/paste-upload";
import {useT} from "@/lib/i18n/locale-provider";
import {useComposerInputSize} from "../hooks/use-composer-input-size";
import {composerCommand} from "../lib/composer-selection";
import {ComposerTransition} from "./composer-transition";
import styles from "./agent-workspace.module.css";

type Props = {
  text: string;
  onText: (value: string) => void;
  onSubmit: () => void;
  canSubmit: boolean;
  submitting: boolean;
  files: ReturnType<typeof useFileUploads>;
  acceptsFiles: boolean;
  mainTools: ReactNode;
  endTools?: ReactNode;
  selections?: ReactNode;
  submitAction?: ReactNode;
  running?: boolean;
  inputRef?: Ref<HTMLTextAreaElement>;
  onCommand?: (command: NonNullable<ReturnType<typeof composerCommand>>) => void;
  placeholder?: string;
  maxLength?: number;
};

/** 普通对话和协作对话共用编辑、附件及发送操作，各自提供当前可用的工具。 */
export function ConversationComposerSurface({text, onText, onSubmit, canSubmit, submitting, files, acceptsFiles, mainTools,
  endTools, selections, submitAction, running = false, inputRef, onCommand, placeholder, maxLength = 20000}: Props) {
  const t = useT();
  const input = useComposerInputSize(text);
  useImperativeHandle(inputRef, () => input.current!, [input]);
  const canSend = canSubmit && !submitting && Boolean(text.trim() || files.fileIds.length);
  const allowUpload = !submitting && acceptsFiles;
  return <form className={styles.composer} data-composer-part="surface" data-composer-location="conversation"
    onSubmit={event => {
      event.preventDefault();
      if (canSend) {
        onSubmit();
      }
    }} onDragOver={event => {
      if (event.dataTransfer.types.includes("Files")) {
        event.preventDefault();
        event.dataTransfer.dropEffect = allowUpload ? "copy" : "none";
      }
    }} onDrop={event => {
      if (event.dataTransfer.types.includes("Files")) {
        event.preventDefault();
        if (allowUpload) {
          files.add(Array.from(event.dataTransfer.files));
        }
      }
    }}>
    <div className={styles.composerBody}>
      <FileUploadSelection uploads={files} disabled={submitting}/>
      {selections}
      <ComposerTransition part="text"><Textarea ref={input} className={styles.composerInput} data-composer-part="text"
        aria-label={t("输入消息")} placeholder={placeholder ?? t("描述你希望完成的任务")} value={text} rows={2}
        maxLength={maxLength} disabled={submitting} onPaste={event => pasteUpload(event, files.add, allowUpload)}
        onChange={event => {
          onText(event.target.value);
          if (onCommand && !(event.nativeEvent as InputEvent).isComposing) {
            const command = composerCommand(event.currentTarget);
            if (command) {
              onCommand(command);
            }
          }
        }} onKeyDown={event => {
          if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing && event.keyCode !== 229 && canSubmit) {
            event.preventDefault();
            if (canSend) {
              onSubmit();
            }
          }
        }}/></ComposerTransition>
    </div>
    <div className={styles.composerTools} data-running={running}>
      <div className={styles.composerMainTools}>{mainTools}</div>
      <div className={styles.composerSubmitTools}>{endTools}
        <ComposerTransition part="submit">{submitAction === undefined ?
          <Button className={styles.sendButton} data-composer-part="submit" type="submit" disabled={!canSend}
            aria-label={t("发送消息")} title={t("发送消息（Enter）")}><IconSend size={19}/></Button> : submitAction}</ComposerTransition>
      </div>
    </div>
  </form>;
}
