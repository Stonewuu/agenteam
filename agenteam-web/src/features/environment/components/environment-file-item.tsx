"use client";

import {Button} from "@/components/ui/button";
import {IconArchive, IconCopy, IconDots, IconDownload, IconEye, IconFileText, IconFolder,
  IconImage, IconPlus, IconTerminal, IconTrash, IconUpload} from "@/components/ui/icons";
import {ContextMenu, ContextMenuContent, ContextMenuTrigger} from "@/components/ui/shadcn/context-menu";
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger} from "@/components/ui/shadcn/dropdown-menu";
import {useT} from "@/lib/i18n/locale-provider";
import type {EnvironmentFile} from "../types/environment";
import {fileKind, formatFileSize} from "../lib/environment-file-format";
import type {FileOperation} from "./environment-file-dialog";
import styles from "./environment-files.module.css";

export function EnvironmentFileItem({base, file, selected, grid, terminalReady, onSelect, onOpen, onParent, onCopy,
  onTerminal, onOperation}: {
  base: string; file: EnvironmentFile; selected: boolean; grid: boolean; terminalReady: boolean;
  onSelect: () => void; onOpen: () => void; onParent: () => void; onCopy: () => void;
  onTerminal: () => void; onOperation: (operation: FileOperation) => void;
}) {
  const t = useT();
  const kind = fileKind(file.name);
  const Icon = file.directory ? IconFolder : kind === "image" ? IconImage : kind === "code" ? IconTerminal
    : kind === "archive" ? IconArchive : IconFileText;
  const menuLabel = t("{0}的更多操作", [file.name]);
  const items = <>
    <DropdownMenuItem onClick={onOpen}>{file.directory ? <IconFolder size={16}/> : <IconEye size={16}/>}
      {t(file.directory ? "打开文件夹" : "查看文件")}</DropdownMenuItem>
    {file.directory ? <>
      <DropdownMenuItem onClick={() => onOperation({kind: "folder", path: file.path})}><IconPlus size={16}/>{t("新建文件夹")}</DropdownMenuItem>
      <DropdownMenuItem onClick={() => onOperation({kind: "upload", path: file.path})}><IconUpload size={16}/>{t("上传文件")}</DropdownMenuItem>
      <DropdownMenuItem disabled={!terminalReady} onClick={onTerminal}><IconTerminal size={16}/>{t("在终端打开")}</DropdownMenuItem>
    </> : <DropdownMenuItem render={<a href={base + "/files/content?" + new URLSearchParams({path: file.path})} download={file.name}/>}>
      <IconDownload size={16}/>{t("下载文件")}</DropdownMenuItem>}
    <DropdownMenuItem onClick={onCopy}><IconCopy size={16}/>{t("复制路径")}</DropdownMenuItem>
    {file.deletable && <><DropdownMenuSeparator/><DropdownMenuItem variant="destructive" onClick={() => onOperation({kind: "delete", file})}>
      <IconTrash size={16}/>{t(file.directory ? "删除文件夹" : "删除文件")}</DropdownMenuItem></>}
  </>;

  return <li><ContextMenu onOpenChange={open => {
    if (open) {
      onSelect();
    }
  }}>
    <ContextMenuTrigger className={styles.entry} data-selected={selected || undefined}>
      <Button className={styles.file} onFocus={onSelect} onClick={onOpen} onKeyDown={event => {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          event.preventDefault();
          const row = event.currentTarget.closest("li");
          const sibling = event.key === "ArrowDown" ? row?.nextElementSibling : row?.previousElementSibling;
          sibling?.querySelector<HTMLButtonElement>("button")?.focus();
        } else if (event.key === "Backspace") {
          event.preventDefault();
          onParent();
        } else if (event.key === "Delete" && file.deletable) {
          event.preventDefault();
          onOperation({kind: "delete", file});
        } else if ((event.shiftKey && event.key === "F10") || event.key === "ContextMenu") {
          event.preventDefault();
          event.currentTarget.parentElement?.querySelector<HTMLButtonElement>("[data-file-actions]")?.click();
        }
      }}>
        <span className={styles.fileIcon} data-icon-surface><Icon size={grid ? 29 : 21} variant="Bulk"/></span>
        <span className={styles.fileName}><strong title={file.name}>{file.name}</strong>
          {file.projectName ? <span className={styles.projectTag} title={t("项目：{0}", [file.projectName])}><IconFolder size={12}/><span>{file.projectName}</span></span>
            : <small>{file.directory ? t("文件夹") : new Date(file.modifiedAt).toLocaleDateString()}</small>}
        </span>
        <span className={styles.fileSize}>{formatFileSize(file.size)}</span>
      </Button>
      <DropdownMenu><DropdownMenuTrigger render={<Button className={styles.more} data-file-actions data-icon-context="inherit" title={menuLabel} aria-label={menuLabel}
        onFocus={onSelect}><IconDots size={17}/></Button>}/>
        <DropdownMenuContent align="end" className={styles.actionMenu}>{items}</DropdownMenuContent>
      </DropdownMenu>
    </ContextMenuTrigger>
    <ContextMenuContent className={styles.actionMenu} aria-label={menuLabel}>{items}</ContextMenuContent>
  </ContextMenu></li>;
}
