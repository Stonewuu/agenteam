"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {IconChevronLeft, IconChevronRight, IconEye, IconFileText, IconFolder, IconRefresh} from "@/components/ui/icons";
import type {ConversationSidebarPanelProps} from "@/features/agent/types/conversation-sidebar";
import {useConversationFiles} from "../hooks/use-conversation-files";
import type {ConversationFileScope, FileBrowserState} from "../types/conversation-files";
import {formatFileSize} from "../lib/file-size";
import {ConversationFilePreview} from "./conversation-file-preview";
import {createFilePreviewTab} from "./conversation-file-preview-tab";
import {findFilePreviewFormat} from "../lib/file-preview-formats";
import styles from "./conversation-files.module.css";

const emptyState: FileBrowserState = {path: "", selected: null};

export function ConversationFileBrowser({
                                          enterprise,
                                          conversation,
                                          active,
                                          running,
                                          refreshKey,
                                          state,
                                          onStateChange,
                                          onOpenTab,
                                          scope
                                        }: ConversationSidebarPanelProps & { scope: ConversationFileScope }) {
  const uiText = useT();
  const current = state && typeof state === "object" && "path" in state ? state as FileBrowserState : emptyState;
  const path = scope === "workspace" ? current.path : "";
  const listing = useConversationFiles(enterprise, conversation, scope, path, active && !current.selected, running, refreshKey);
  if (!active) {
    return null;
  }
  if (!conversation) {
    return <p className={styles.empty}>{uiText("开始对话后，即可在这里查看文件。")}</p>;
  }
  if (current.selected) {
    return <ConversationFilePreview key={current.selected.id} enterprise={enterprise} conversation={conversation}
                                    file={current.selected}
                                    onPreview={(file) => onOpenTab(createFilePreviewTab(file))}
                                    onBack={() => onStateChange({...current, selected: null})}/>;
  }
  const parent = path.includes("/") ? path.substring(0, path.lastIndexOf("/")) : "";
  return <section className={styles.browser}
                  aria-label={scope === "workspace" ? uiText("工作区文件列表") : uiText("会话文件列表")}>
    <header className={styles.listHeading}>
      {path && <Button type="button" className="icon-button" aria-label={uiText("返回上级目录")}
                       onClick={() => onStateChange({path: parent, selected: null})}><IconChevronLeft
        size={17}/></Button>}
      <span
        title={path || (scope === "workspace" ? uiText("工作区") : uiText("会话文件"))}>{path || (scope === "workspace" ? uiText("工作区") : uiText("会话文件"))}</span>
      <Button type="button" className="icon-button" aria-label={uiText("刷新文件列表")} disabled={listing.loading}
              onClick={listing.reload}><IconRefresh size={17}/></Button>
    </header>
    {listing.error &&
      <div className={styles.error} role="alert">{localizeUiMessage(listing.error ?? "", uiText)}<Button type="button"
                                                                                                         onClick={listing.reload}>{uiText("重新加载")}</Button>
      </div>}
    <div className={styles.fileList}>
      {listing.items.length === 0 && !listing.error && <p className={styles.empty}
                                                          role={listing.loading ? "status" : undefined}>{listing.loading ? uiText("正在读取文件列表…")
        : scope === "context" ? uiText("此对话还没有上传或产出文件。") : path ? uiText("此文件夹为空。") : uiText("此对话还没有工作文件。")}</p>}
      <ul>{listing.items.map((file) => <li className={styles.fileRow} key={file.id}>
        <Button type="button" className={styles.fileItem} title={file.path}
                aria-label={(file.directory ? uiText("打开文件夹 ") : uiText("查看文件 ")) + file.name}
                onClick={() => onStateChange(file.directory ? {path: file.path, selected: null} : {
                  ...current,
                  selected: file
                })}>
          <span className={styles.fileIcon}>{file.directory ? <IconFolder size={20}/> :
            <IconFileText size={20}/>}</span>
          <span className={styles.fileName}><strong>{file.name}</strong>{!file.directory &&
            <small>{scope === "context" ? ({
              uploaded: uiText("上传"),
              generated: uiText("产出"),
              workspace: uiText("工作文件")
            }[file.source]) + " · " : ""}{formatFileSize(file.sizeBytes, uiText)}</small>}</span>
          {file.directory && <IconChevronRight size={15}/>}
        </Button>
        {findFilePreviewFormat(file) &&
          <Button type="button" className={styles.filePreviewAction} title={uiText("预览 ") + file.name}
                  aria-label={uiText("预览文件 ") + file.name}
                  onClick={() => onOpenTab(createFilePreviewTab(file))}><IconEye size={18}/></Button>}
      </li>)}</ul>
      {listing.hasMore &&
        <Button type="button" className={styles.loadMore} disabled={listing.loadingMore || listing.loading}
                onClick={() => void listing.loadMore()}>{listing.loadingMore ? uiText("正在读取…") : uiText("加载更多文件")}</Button>}
    </div>
  </section>;
}
