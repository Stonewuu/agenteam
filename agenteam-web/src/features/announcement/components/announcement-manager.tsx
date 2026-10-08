"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {type ReactNode, useId, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {RichTextEditor} from "@/components/ui/rich-text-editor";
import {RichTextContent} from "@/components/ui/rich-text-content";
import {richTextDocument, richTextPreview} from "@/components/ui/rich-text-document";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";
import {Select} from "@/components/ui/select";
import {PageHeader} from "@/components/ui/page-header";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {IconBuilding, IconPlus, IconRefresh, IconShield, IconTrash} from "@/components/ui/icons";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {
  type Announcement,
  type AnnouncementLevel,
  type AnnouncementManagementPage,
  announcementTitle
} from "../types/announcement";
import styles from "./announcement.module.css";
import ui from "@/components/ui/surface.module.css";

export function AnnouncementManager({endpoint, title: providedTitle, children}: {
  endpoint: string | null;
  title?: string;
  children?: ReactNode
}) {
  const uiText = useT();
  const title = providedTitle ?? uiText("企业公告");
  const [refresh, setRefresh] = useState(0);
  const [cursor, setCursor] = useState<string | null>(null);
  const [previous, setPrevious] = useState<(string | null)[]>([]);
  const [editing, setEditing] = useState<Announcement | "new" | null>(null);
  const [deleting, setDeleting] = useState<Announcement | null>(null);
  const list = useApiQuery<AnnouncementManagementPage>(endpoint ? `${endpoint}?limit=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}` : null, refresh);
  const action = useFormAction();
  const [previousEndpoint, setPreviousEndpoint] = useState(endpoint);
  // 数据范围变化时清空操作状态，保留范围页签及其选中背景。
  if (previousEndpoint !== endpoint) {
    setPreviousEndpoint(endpoint);
    setCursor(null);
    setPrevious([]);
    setEditing(null);
    setDeleting(null);
    action.resetFeedback();
  }
  const changed = () => {
    setEditing(null);
    setDeleting(null);
    setRefresh((value) => value + 1);
    window.dispatchEvent(new Event("agenteam:notifications-changed"));
  };
  return <>
    <PageHeader title={title} description={uiText("查看公告内容、提醒等级与启用状态。")}
                actions={<div className={ui.actions}>
                  <Button className="icon-button" aria-label={uiText("刷新公告")} disabled={!endpoint || list.loading}
                          onClick={() => setRefresh((value) => value + 1)}><IconRefresh size={18}/></Button>
                  {list.data?.canManage && <Button className={ui.primary} onClick={() => setEditing("new")}><IconPlus
                    size={17}/>{uiText("新建公告")}</Button>}
                </div>}/>
    {children}
    {!endpoint ? <p className={ui.empty}>{uiText("请选择企业。")}</p> : <>
      <MutationFeedback action={action} onReload={changed}/>
      <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("暂无公告。")}>
        <div className={styles.list}>{list.data?.items.map((value) => {
          const Icon = value.scope === "platform" ? IconShield : IconBuilding;
          return <article className={styles.card} key={value.id}><span className={styles.scopeIcon}><Icon
            size={23}/></span>
            <div className={styles.cardContent}><h2 className={styles.cardTitle}>{announcementTitle(value, uiText)}</h2>
              <p className={styles.preview}>{richTextPreview(value)}</p>
              <div className={styles.metadata}>
                <span>{value.level.name}</span><span>{value.enabled ? uiText("已启用") : uiText("未启用")}</span>
                {value.publisherName && <span>{uiText("发布人：")}{value.publisherName}</span>}
                <EnterpriseDateTime value={value.updatedAt}/></div>
              <div className={styles.cardActions}><Button className={ui.button}
                                                          onClick={() => setEditing(value)}>{list.data?.canManage && !value.enabled ? uiText("编辑") : uiText("查看")}</Button>
                {list.data?.canManage &&
                  <Button className={value.enabled ? ui.button : ui.primary} disabled={action.busy}
                          onClick={() => void action.execute(async () => {
                            await action.mutation.run(`${endpoint}/${encodeURIComponent(value.id)}/status`, {
                              method: "POST",
                              revision: value.revision,
                              body: {enabled: !value.enabled}
                            });
                            changed();
                          }, value.enabled ? uiText("公告已停用。") : uiText("公告已启用。"))}>{value.enabled ? uiText("停用") : uiText("启用")}</Button>}
                {list.data?.canManage &&
                  <Button className={ui.danger} disabled={action.busy} onClick={() => setDeleting(value)}><IconTrash
                    size={15}/>{uiText("删除")}</Button>}
              </div>
            </div>
          </article>;
        })}</div>
      </QueryState>
      <Pagination previous={previous} hasMore={list.data?.hasMore} loading={list.loading || action.busy}
                  back={() => {
                    setCursor(previous.at(-1) ?? null);
                    setPrevious((value) => value.slice(0, -1));
                  }} next={() => {
        if (list.data?.hasMore && list.data.nextCursor) {
          setPrevious((value) => [...value, cursor]);
          setCursor(list.data.nextCursor);
        }
      }}/>
    </>}
    {endpoint && editing && list.data &&
      <AnnouncementEditor key={editing === "new" ? "new" : editing.id} endpoint={endpoint}
                          initial={editing === "new" ? null : editing} levels={list.data.levels}
                          readOnly={!list.data.canManage || (editing !== "new" && editing.enabled)}
                          onClose={() => setEditing(null)} onSaved={() => {
        setCursor(null);
        setPrevious([]);
        changed();
      }}/>}
    {endpoint && deleting &&
      <AnnouncementDeleteDialog endpoint={endpoint} value={deleting} onClose={() => setDeleting(null)}
                                onDeleted={() => {
                                  setCursor(null);
                                  setPrevious([]);
                                  changed();
                                }}/>}
  </>;
}

function AnnouncementEditor({endpoint, initial, levels, readOnly, onClose, onSaved}: {
  endpoint: string;
  initial: Announcement | null;
  levels: AnnouncementLevel[];
  readOnly: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const uiText = useT();
  const [title, setTitle] = useState(initial?.title ?? "");
  const [content, setContent] = useState(initial?.content ?? "");
  const [contentFormat, setContentFormat] = useState(initial?.contentFormat ?? "plain_text");
  const [initialDocument] = useState(() => richTextDocument(initial ?? {content: ""}));
  const [contentError, setContentError] = useState("");
  const editorId = useId();
  const [level, setLevel] = useState(initial?.level.code ?? levels.find((value) => value.code === "general")?.code ?? levels.at(-1)?.code ?? "");
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Dialog title={readOnly ? uiText("查看公告") : initial ? uiText("编辑公告") : uiText("新建公告")}
                 onClose={onClose} dialogRef={dialog.ref} busy={action.busy} wide>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      if (readOnly) {
        return;
      }
      const text = richTextPreview({content, contentFormat});
      const message = !text.replace(/[\s\u200b\ufeff]/g, "") ? uiText("请填写公告正文。")
        : text.length > 20000 ? uiText("正文最多20000个字符。") : content.length > 200000 ? uiText("正文排版过多，请精简后重试。") : "";
      if (message) {
        setContentError(message);
        document.getElementById(editorId)?.focus();
        return;
      }
      void action.execute(async () => {
        await action.mutation.run(initial ? `${endpoint}/${encodeURIComponent(initial.id)}` : endpoint,
          {
            method: initial ? "PUT" : "POST",
            revision: initial?.revision,
            body: {title, content, contentFormat, level}
          });
        dialog.close(onSaved);
      }, uiText("公告已保存。"));
    }}>
      <label className={ui.field}><span>{uiText("标题")}</span><Input name="title" required value={title}
                                                                      maxLength={160} disabled={readOnly || action.busy}
                                                                      onChange={(event) => setTitle(event.target.value)}/></label>
      <label className={ui.field}><span>{uiText("提醒等级")}</span><Select name="level" value={level}
                                                                           disabled={readOnly || action.busy}
                                                                           onChange={(event) => setLevel(event.target.value)}>
        {levels.map((value) => <option key={value.code} value={value.code}>{value.name}</option>)}</Select></label>
      <div className={ui.field}><label htmlFor={editorId}>{uiText("正文")}</label>
        {readOnly ? <RichTextContent value={{content, contentFormat}}/> :
          <RichTextEditor id={editorId} initial={initialDocument}
                          disabled={action.busy} invalid={Boolean(contentError || action.fieldErrors.content?.length)}
                          errorId={`${editorId}-error`}
                          onChange={(document) => {
                            setContent(JSON.stringify(document));
                            setContentFormat("rich_text");
                            setContentError("");
                            action.clearFieldError("content");
                          }}/>}
        <FieldErrorFeedback id={`${editorId}-error`}
                            messages={contentError ? [contentError] : action.fieldErrors.content ?? []}/>
      </div>
      {!readOnly &&
        <p className={ui.description}>{uiText("保存后需启用才会展示给用户。修改内容后再次启用，会重新提醒已读用户。")}</p>}
      <MutationFeedback action={action} onReload={onSaved}/>
      <DialogActions className={ui.footer}><DialogCancel type="button"
                                                         disabled={action.busy}>{readOnly ? uiText("关闭") : uiText("取消")}</DialogCancel>
        {!readOnly && <Button className={ui.primary}
                              disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存")}</Button>}
      </DialogActions>
    </DialogForm>
  </Dialog>;
}

function AnnouncementDeleteDialog({endpoint, value, onClose, onDeleted}: {
  endpoint: string; value: Announcement; onClose: () => void; onDeleted: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  return <Dialog title={uiText("删除公告")} onClose={onClose} busy={action.busy} dialogRef={dialog.ref} size="small">
    <p>{uiText("确定删除“")}{value.title}{uiText("”吗？删除后，用户将无法查看此公告，也不会再收到提醒。")}</p>
    <MutationFeedback action={action} onReload={onDeleted}/>
    <DialogActions><DialogCancel type="button" disabled={action.busy}>{uiText("取消")}</DialogCancel>
      <Button type="button" className={ui.danger} disabled={action.busy}
              onClick={() => void action.execute(async () => {
                await action.mutation.run(`${endpoint}/${encodeURIComponent(value.id)}`, {
                  method: "DELETE",
                  revision: value.revision
                });
                dialog.close(onDeleted);
              }, uiText("公告已删除。"))}>{action.busy ? uiText("正在删除…") : uiText("删除公告")}</Button>
    </DialogActions>
  </Dialog>;
}
