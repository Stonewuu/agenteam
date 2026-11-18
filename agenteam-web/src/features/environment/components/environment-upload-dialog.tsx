"use client";

import {useEffect, useRef, useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Dialog, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {FilePicker} from "@/components/ui/file-picker";
import {IconFolder, IconUpload} from "@/components/ui/icons";
import {toast} from "@/components/ui/toast";
import {useT} from "@/lib/i18n/locale-provider";
import {useEnvironmentFileDrop} from "../hooks/use-environment-file-drop";
import {localFolderPicker, pendingUpload, selectedFiles, selectedFolder, type PendingUpload, type UploadSource} from "../lib/environment-upload-selection";
import {uploadContents} from "../lib/environment-upload";
import {EnvironmentUploadItem} from "./environment-upload-item";
import ui from "@/components/ui/surface.module.css";
import styles from "./environment-files.module.css";

function appendSources(current: PendingUpload[], sources: UploadSource[]) {
  const names = new Set(current.map(item => item.source.name));
  const added = sources.filter(source => {
    if (names.has(source.name)) {
      return false;
    }
    names.add(source.name);
    return true;
  });
  return [...current, ...added.map(pendingUpload)];
}

export function EnvironmentUploadDialog({base, path, initialSources, onClose, onChanged}: {
  base: string; path: string; initialSources?: UploadSource[]; onClose: () => void; onChanged: () => void;
}) {
  const t = useT();
  const dialog = useDialogControl();
  const [pending, setPending] = useState(() => appendSources([], initialSources ?? []));
  const [activePath, setActivePath] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const busy = activePath !== null;
  const unread = pending.find(item => !item.contents && !item.error);
  const picker = localFolderPicker();

  useEffect(() => {
    if (unread?.source.kind !== "directory") {
      return;
    }
    const controller = new AbortController();
    void unread.source.scan(controller.signal).then(contents => {
      if (!controller.signal.aborted) {
        setPending(items => items.map(item => item === unread ? {...item, contents} : item));
      }
    }).catch(failure => {
      if (!controller.signal.aborted) {
        console.error("Could not read local upload directory", failure);
        setPending(items => items.map(item => item === unread ? {...item, error: t("无法读取此文件夹，请移除后重新选择。")} : item));
      }
    });
    return () => controller.abort();
  }, [unread, t]);

  function add(sources: UploadSource[]) {
    if (request.current) {
      return;
    }
    const names = new Set([...pending.map(item => item.source.name), ...sources.map(source => source.name)]);
    if (names.size < pending.length + sources.length) {
      toast.info(t("待上传列表中已有同名文件或文件夹。"));
    }
    setPending(items => appendSources(items, sources));
  }

  async function chooseFolder() {
    if (!picker) {
      return;
    }
    try {
      const handle = await picker({mode: "read"});
      add([selectedFolder(handle)]);
    } catch (failure) {
      if (failure instanceof DOMException && failure.name === "AbortError") {
        return;
      }
      console.error("Could not open local folder picker", failure);
      toast.error(t("无法打开文件夹选择窗口，请将文件夹拖到此处。"));
    }
  }

  const drop = useEnvironmentFileDrop(!busy, add);
  useEffect(() => () => request.current?.abort(), []);

  async function upload() {
    if (request.current || !pending.length || pending.some(item => !item.contents)) {
      return;
    }
    const controller = new AbortController();
    request.current = controller;
    let hasFailures = false;
    let changed = false;
    let uploaded = 0;
    try {
      for (const item of pending) {
        controller.signal.throwIfAborted();
        setActivePath(item.source.name);
        const result = await uploadContents(base, path, item.contents!, controller.signal, setActivePath, t("无法读取本地文件，请重新选择。"));
        controller.signal.throwIfAborted();
        changed ||= result.changed;
        uploaded += result.uploaded;
        if (result.remaining) {
          hasFailures = true;
          const retry = {...item, contents: result.remaining, error: result.error};
          setPending(items => items.map(current => current === item ? retry : current));
        } else {
          setPending(items => items.filter(current => current !== item));
        }
      }
      if (changed || !hasFailures) {
        onChanged();
      }
      if (!hasFailures) {
        toast.success(pending.some(item => item.source.kind === "directory") ? t("上传完成")
          : uploaded === 1 ? t("文件已上传") : t("已上传 {0} 个文件", [uploaded]));
        dialog.close();
      } else if (uploaded) {
        toast.success(t("已上传 {0} 个文件", [uploaded]));
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        console.error("Could not finish environment upload", failure);
        toast.error(t("上传中断，请刷新目录后重试。"));
      }
    } finally {
      request.current = null;
      if (!controller.signal.aborted) {
        setActivePath(null);
      }
    }
  }

  const hasFolder = pending.some(item => item.source.kind === "directory");
  return <Dialog title={t("上传文件")} icon={<IconUpload size={20}/>} size="medium" dialogRef={dialog.ref} busy={busy} onClose={onClose} dropHandlers={drop.handlers}
    footer={<><DialogCancel className={ui.button} disabled={busy}>{t("取消")}</DialogCancel>
      <Button className={ui.primary} disabled={!pending.length || busy || pending.some(item => !item.contents)} onClick={() => void upload()}>
        {busy ? t("正在上传…") : pending.length > 1 ? t(hasFolder ? "上传 {0} 项" : "上传 {0} 个文件", [pending.length]) : t("上传")}
      </Button></>}>
    <AnimatedHeight preserveControlShadows><div className={styles.operationForm}>
      <div className={styles.targetPath}><IconFolder size={16}/><span>{path}</span></div>
      <div className={styles.uploadZone} data-dragging={drop.dragging || undefined}>
        <IconUpload size={25}/><strong>{t(drop.dragging ? "松开以添加文件或文件夹" : "将文件或文件夹拖到这里")}</strong>
        <div className={styles.pickActions}><FilePicker multiple showSelection={false} disabled={busy} onChange={event => {
          add(selectedFiles(Array.from(event.target.files ?? [])));
          event.target.value = "";
        }}/>
          {picker && <Button className={ui.button} disabled={busy} onClick={() => void chooseFolder()}><IconFolder size={17}/>{t("选择文件夹")}</Button>}
        </div>
      </div>
      {pending.length > 0 && <ul className={styles.uploadList} aria-label={t("待上传文件")}>
        {pending.map(item => <EnvironmentUploadItem key={item.source.name} item={item} activePath={activePath} busy={busy}
          onRemove={() => setPending(items => items.filter(other => other.source !== item.source))}/>)}
      </ul>}
    </div></AnimatedHeight>
  </Dialog>;
}
