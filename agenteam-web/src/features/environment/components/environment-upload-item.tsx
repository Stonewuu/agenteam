"use client";

import {useMemo, useState} from "react";
import {Button} from "@/components/ui/button";
import {IconChevronDown, IconChevronRight, IconFileText, IconFolder, IconX} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {formatFileSize} from "../lib/environment-file-format";
import type {PendingUpload, UploadContents} from "../lib/environment-upload-selection";
import styles from "./environment-files.module.css";
import ui from "@/components/ui/surface.module.css";

function FolderPaths({name, contents}: {name: string; contents: UploadContents}) {
  const t = useT();
  const [limit, setLimit] = useState(50);
  const entries = useMemo(() => {
    const dirs = contents.directories.filter(path => path !== name).map(path => ({path, directory: true, error: ""}));
    const files = contents.files.map(file => ({path: file.path, directory: false, error: file.error ?? ""}));
    return [...dirs, ...files].sort((a, b) => Number(Boolean(b.error)) - Number(Boolean(a.error)) || a.path.localeCompare(b.path));
  }, [contents, name]);
  return <div className={styles.folderPaths}>
    <ul aria-label={t("文件夹内容")}>{entries.slice(0, limit).map(entry => <li key={entry.path}>
      {entry.directory ? <IconFolder size={15}/> : <IconFileText size={15}/>}
      <div><span>{entry.path.slice(name.length + 1)}</span>{entry.error && <small className={styles.pathError}>{entry.error}</small>}</div>
    </li>)}</ul>
    {entries.length > limit && <Button className={styles.smallButton} onClick={() => setLimit(value => value + 50)}>{t("显示更多")}</Button>}
  </div>;
}

export function EnvironmentUploadItem({item, activePath, busy, onRemove}: {
  item: PendingUpload; activePath: string | null; busy: boolean; onRemove: () => void;
}) {
  const t = useT();
  const [open, setOpen] = useState<boolean | null>(null);
  const {source, contents} = item;
  const directory = source.kind === "directory";
  const name = source.name;
  const active = activePath === name || (directory && activePath?.startsWith(name + "/"));
  const failures = useMemo(() => contents?.files.filter(file => file.error).length ?? 0, [contents]);
  const expanded = open ?? (failures > 0);
  const canExpand = directory && contents && (contents.files.length > 0 || contents.directories.length > 1);
  let detail = "";
  if (active) {
    detail = activePath === name ? t("正在上传…") : activePath ?? "";
  } else if (!contents) {
    detail = item.error ? "" : t("正在读取目录…");
  } else if (!directory) {
    const size = contents.files[0]?.size;
    detail = size == null ? "" : formatFileSize(size);
  } else {
    detail = canExpand ? t("{0} 个文件 · {1} 个子文件夹", [contents.files.length, contents.directories.length - 1]) : t("空文件夹");
  }
  const error = item.error || (!directory ? contents?.files[0]?.error : failures ? t("还有 {0} 个文件待上传", [failures]) : "");
  const info = <>{directory ? <IconFolder size={18}/> : <IconFileText size={18}/>}
    <span className={styles.uploadInfo}><strong title={name}>{name}</strong>{detail && <small>{detail}</small>}</span></>;
  return <li><div className={styles.uploadRow}>
    {canExpand ? <Button className={styles.uploadGroup} aria-expanded={expanded} aria-label={t("查看 {0} 的内容", [name])}
      onClick={() => setOpen(!expanded)}>{info}{expanded ? <IconChevronDown size={14}/> : <IconChevronRight size={14}/>}</Button>
      : <div className={styles.uploadGroup}>{info}</div>}
    <Button className={styles.iconButton} disabled={busy} title={t("移除")} aria-label={t("移除 {0}", [name])} onClick={onRemove}><IconX size={15}/></Button>
  </div>{error && <p className={ui.error} role="alert">{error}</p>}
    {expanded && canExpand && <FolderPaths name={name} contents={contents}/>}
  </li>;
}
