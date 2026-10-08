"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useEffect, useState} from "react";
import {Dialog, DialogAction, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useFileUploads} from "@/features/file/hooks/use-file-uploads";
import {FileUploadPicker} from "@/features/file/components/file-upload-picker";
import {downloadFile} from "@/features/file/api/file-api";
import {formatFileSize} from "@/features/file/lib/file-size";
import type {KnowledgeDocument} from "../types/knowledge";
import {KnowledgeSearchForm} from "./knowledge-search-dialog";
import {Tabs} from "@/components/ui/tabs";
import {MotionPanel} from "@/components/ui/motion-panel";
import {SearchInput} from "@/components/ui/search-input";
import {IconDots, IconDownload, IconFileText, IconRefresh, IconTrash, IconUpload} from "@/components/ui/icons";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/shadcn/dropdown-menu";
import {KnowledgeReplaceFileDialog} from "./knowledge-replace-file-dialog";
import ui from "@/components/ui/surface.module.css";
import styles from "./knowledge.module.css";

export function KnowledgeDocumentsPanel({enterpriseId, resourceId, editable, searchable, dirty}: {
  enterpriseId: string; resourceId: string; editable: boolean; searchable: boolean; dirty: boolean;
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [adding, setAdding] = useState(false);
  const [replacing, setReplacing] = useState<KnowledgeDocument | null>(null);
  const [tab, setTab] = useState("documents");
  const [removing, setRemoving] = useState<KnowledgeDocument | null>(null);
  const action = useFormAction();
  const path = organizationPath(enterpriseId, `/knowledge/${encodeURIComponent(resourceId)}/documents`);
  const docs = useApiPage<KnowledgeDocument>(`${path}?query=${encodeURIComponent(query)}`, refresh);
  const processing = Boolean(docs.data?.items.some((item) => item.status === "queued" || item.status === "processing"));
  useEffect(() => {
    if (!processing || docs.error) {
      return;
    }
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        setRefresh((value) => value + 1);
      }
    }, 2000);
    return () => window.clearInterval(timer);
  }, [processing, docs.error]);
  return <section className={styles.panel}>
    <div className="form-section-title"><h2>{uiText("文档管理")}</h2><p>{uiText("上传团队资料，查找可引用的内容。")}</p>
    </div>
    <header className={styles.heading}><Tabs value={tab} onChange={setTab} items={[{
      value: "documents",
      label: uiText("文档")
    }, ...(searchable ? [{value: "search", label: uiText("检索测试")}] : [])]}/>
      <div className={ui.actions}>
        {editable && <Button type="button" className={ui.primary} disabled={dirty || action.busy}
                             onClick={() => setAdding(true)}><IconUpload size={16}/>{uiText("上传文档")}</Button>}
      </div>
    </header>
    {dirty && editable && <p className={ui.description}>{uiText("请先保存上方修改，再管理文档。")}</p>}
    <MotionPanel value={tab} className={styles.documentView}>
      {tab === "search" && searchable ? <KnowledgeSearchForm enterpriseId={enterpriseId} resourceId={resourceId}/> : <>
        <SearchInput placeholder={uiText("搜索文档名称…")} aria-label={uiText("搜索文档")} value={query} maxLength={100}
                     onChange={(event) => setQuery(event.target.value)}/>
        <MutationFeedback action={action} onReload={docs.retry}/>
        <QueryState {...docs} hasData={Boolean(docs.data?.items.length)}
                    empty={query ? uiText("没有找到匹配的文档。") : uiText("还没有文档。")}>
          <ul className={styles.documents}>{docs.data?.items.map((doc) => <li key={doc.id}>
            <span className={styles.documentIcon}><IconFileText size={24}/></span>
            <div className={styles.document}>
              <strong>{doc.name}</strong><span>{formatFileSize(doc.sizeBytes, uiText)} · <EnterpriseDateTime
              value={doc.updatedAt} dateOnly/></span>{doc.errorSummary &&
              <p className={ui.error}>{doc.errorSummary}</p>}</div>
            <span
              className={`badge ${doc.status === "failed" ? "red" : doc.status === "ready" ? "mint" : "amber"}`}>{uiText(documentStatus(doc))}</span>
            <DropdownMenu><DropdownMenuTrigger
              render={<Button type="button" className="icon-button" aria-label={uiText("{0}的操作", [doc.name])}
                              disabled={action.busy}><IconDots size={18}/></Button>}/>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  onClick={() => void action.execute(() => downloadFile(enterpriseId, doc.activeFileId ?? doc.fileId), "")}><IconDownload
                  size={16}/>{uiText("下载文档")}</DropdownMenuItem>
                {editable && <><DropdownMenuItem disabled={dirty || action.busy || doc.pendingGeneration > 0}
                                                 onClick={() => setReplacing(doc)}><IconUpload
                  size={16}/>{uiText("更新文件")}</DropdownMenuItem>
                  <DropdownMenuItem
                    disabled={dirty || action.busy || doc.status === "queued" || doc.status === "processing"}
                    onClick={() => void action.execute(async () => {
                      await action.mutation.run(`${path}/${encodeURIComponent(doc.id)}/reprocess`, {
                        method: "POST",
                        revision: doc.revision
                      });
                      setRefresh((value) => value + 1);
                    }, uiText("已开始重新处理。"))}><IconRefresh size={16}/>{uiText("重新处理")}
                  </DropdownMenuItem><DropdownMenuItem disabled={dirty || action.busy} onClick={() => setRemoving(doc)}><IconTrash
                    size={16}/>{uiText("删除文档")}</DropdownMenuItem></>}
              </DropdownMenuContent></DropdownMenu>
          </li>)}</ul>
        </QueryState>
        <Pagination {...docs} hasMore={docs.data?.hasMore}/>
      </>}
    </MotionPanel>
    {adding && <UploadDocuments enterpriseId={enterpriseId} resourceId={resourceId} onClose={() => setAdding(false)}
                                onAdded={() => {
                                  setAdding(false);
                                  docs.first();
                                  setRefresh((value) => value + 1);
                                }}/>}
    {replacing && <KnowledgeReplaceFileDialog enterpriseId={enterpriseId} resourceId={resourceId} document={replacing}
                                              onClose={() => setReplacing(null)} onReplaced={() => {
      setReplacing(null);
      setRefresh((value) => value + 1);
    }}/>}
    {removing &&
      <Dialog title={uiText("删除“{0}”？", [removing.name])} onClose={() => setRemoving(null)} busy={action.busy}><p
        className={ui.description}>{uiText("删除后将无法检索或打开这份文档的引用。")}</p><MutationFeedback
        action={action}/>
        <DialogActions className={ui.footer}><DialogCancel type="button" className={ui.button}
                                                           disabled={action.busy}>{uiText("取消")}</DialogCancel><DialogAction
          type="button" className={ui.danger} disabled={action.busy}
          onAction={(close) => void action.execute(async () => {
            await action.mutation.run(`${path}/${encodeURIComponent(removing.id)}`, {
              method: "DELETE",
              revision: removing.revision
            });
            close(() => {
              setRemoving(null);
              setRefresh((value) => value + 1);
            });
          }, uiText("文档已删除。"))}>{uiText("删除文档")}</DialogAction></DialogActions></Dialog>}
  </section>;
}

function UploadDocuments({enterpriseId, resourceId, onClose, onAdded}: {
  enterpriseId: string;
  resourceId: string;
  onClose: () => void;
  onAdded: () => void
}) {
  const uiText = useT();
  const uploads = useFileUploads(enterpriseId, "knowledge", "add-documents", resourceId);
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Dialog title={uiText("添加文档")} onClose={onClose} dialogRef={dialog.ref} busy={action.busy}><DialogForm
    className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    if (!uploads.ready) {
      return;
    }
    void action.execute(async () => {
      await action.mutation.run(organizationPath(enterpriseId, `/knowledge/${encodeURIComponent(resourceId)}/documents`), {
        method: "POST",
        body: {fileIds: uploads.fileIds}
      });
      dialog.close(() => {
        uploads.clear();
        onAdded();
      });
    }, "");
  }}><p
    className={ui.description}>{uiText("支持含文字的 PDF、Word（.docx）、TXT 和 Markdown。单份不超过 20 兆字节，每批最多 10 份。")}</p>
    <FileUploadPicker uploads={uploads} purpose="knowledge" disabled={action.busy}/><MutationFeedback action={action}/>
    <DialogActions className={ui.footer}><DialogCancel type="button" className={ui.button}
                                                       disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
      className={ui.primary}
      disabled={!uploads.ready || action.busy}>{action.busy ? uiText("正在添加…") : uiText("添加文档")}</Button></DialogActions>
  </DialogForm></Dialog>;
}

function documentStatus(doc: KnowledgeDocument) {
  if (doc.status === "queued") {
    return "等待处理";
  }
  if (doc.status === "processing") {
    return doc.activeGeneration > 0 ? "正在更新内容" : "正在处理";
  }
  if (doc.status === "failed") {
    return doc.activeGeneration > 0 ? "更新未完成，已有内容仍可检索" : "处理未完成";
  }
  return "可检索";
}
