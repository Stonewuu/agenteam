"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {useState} from "react";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import type {Tag} from "../types/resource";
import {TextField} from "./resource-fields";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export function TagPicker({enterpriseId, selected, onChange, known = [], canManage = false, readOnly = false}: {
  enterpriseId: string;
  selected: string[];
  onChange: (values: string[]) => void;
  known?: Tag[];
  canManage?: boolean;
  readOnly?: boolean;
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [cached, setCached] = useState<Tag[]>([]);
  const [editing, setEditing] = useState<Tag | "new" | null>(null);
  const list = useApiPage<Tag>(organizationPath(enterpriseId, `/tags?query=${encodeURIComponent(query)}`), refresh);
  const names = new Map([...known, ...cached, ...(list.data?.items ?? [])].map((tag) => [tag.id, tag.name]));
  return <Fieldset className={styles.section}>
    <legend>{uiText("标签")}</legend>
    <div className={ui.chips}>{selected.map((id, index) => readOnly ?
      <span className={ui.chip} key={id}>{names.get(id) ?? uiText("已选标签 {0}", [index + 1])}</span>
      : <Button className={ui.button} type="button" key={id}
                aria-label={uiText("移除{0}", [names.get(id) ?? uiText("标签 {0}", [index + 1])])}
                onClick={() => onChange(selected.filter((value) => value !== id))}>{names.get(id) ?? uiText("已选标签 {0}", [index + 1])} ×</Button>)}</div>
    {!readOnly && <Button className={ui.button} type="button" onClick={() => setOpen((value) => !value)}
                          aria-expanded={open}>{open ? uiText("收起标签") : uiText("选择标签")}</Button>}
    {open && <div className={styles.options}>
      <div className={styles.line}><Input className={ui.input} type="search" aria-label={uiText("搜索标签")}
                                          placeholder={uiText("搜索标签")} value={query}
                                          onChange={(event) => setQuery(event.target.value)}/>
        {canManage &&
          <Button className={ui.button} type="button" onClick={() => setEditing("new")}>{uiText("新建标签")}</Button>}
      </div>
      <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={uiText("暂无匹配的标签。")}>
        <div className={styles.optionList}>{list.data?.items.map((tag) => <div className={styles.line} key={tag.id}>
          <label className={ui.check}><Checkbox checked={selected.includes(tag.id)}
                                                disabled={!selected.includes(tag.id) && selected.length >= 10}
                                                onCheckedChange={(checked) => {
                                                  setCached((values) => [...values.filter((value) => value.id !== tag.id), tag]);
                                                  onChange(checked ? [...selected, tag.id] : selected.filter((id) => id !== tag.id));
                                                }}/>{tag.name}</label>{canManage &&
          <Button className={ui.button} type="button" onClick={() => setEditing(tag)}>{uiText("编辑")}</Button>}
        </div>)}</div>
      </QueryState><Pagination {...list} hasMore={list.data?.hasMore}/></div>}
    {editing && <TagDialog key={typeof editing === "string" ? editing : editing.id} enterpriseId={enterpriseId}
                           tag={editing === "new" ? null : editing} onClose={() => setEditing(null)}
                           onChanged={(tag, removed) => {
                             setRefresh((value) => value + 1);
                             if (tag) {
                               setCached((values) => [...values.filter((value) => value.id !== tag.id), tag]);
                               if (editing === "new" && selected.length < 10) {
                                 onChange([...selected, tag.id]);
                               }
                             }
                             if (removed) {
                               onChange(selected.filter((id) => id !== removed));
                             }
                             setEditing(null);
                           }}/>}
  </Fieldset>;
}

function TagDialog({enterpriseId, tag, onClose, onChanged}: {
  enterpriseId: string;
  tag: Tag | null;
  onClose: () => void;
  onChanged: (tag: Tag | null, removed?: string) => void
}) {
  const uiText = useT();
  const action = useFormAction();
  const [name, setName] = useState(tag?.name ?? "");
  const [confirm, setConfirm] = useState<"delete" | "discard" | null>(null);
  useBlockNavigation(name !== (tag?.name ?? ""), action.busy);
  const dialog = useDialogControl(), confirmation = useDialogControl();
  const finish = (complete: () => void) => {
    confirmation.close();
    dialog.close(complete);
  };
  return <Dialog title={tag ? uiText("编辑标签") : uiText("新建标签")} onClose={onClose} dialogRef={dialog.ref}
                 onRequestClose={() => {
                   if (name !== (tag?.name ?? "")) {
                     setConfirm("discard");
                     return false;
                   }
                   return true;
                 }} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        const saved = await action.mutation.run<Tag>(organizationPath(enterpriseId, `/tags${tag ? `/${encodeURIComponent(tag.id)}` : ""}`), {
          method: tag ? "PATCH" : "POST",
          revision: tag?.revision,
          body: {name}
        });
        finish(() => onChanged(saved));
      });
    }}><TextField label={uiText("标签名称")} name="name" value={name} onChange={setName} maximum={20} required
                  errors={action.fieldErrors}/>
      <MutationFeedback action={action} onReload={() => finish(onClose)}/><DialogActions className={ui.footer}>{tag &&
        <Button className={ui.danger} type="button" disabled={action.busy}
                onClick={() => setConfirm("delete")}>{uiText("删除标签")}</Button>}
        <DialogCancel className={ui.button} disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
          className={ui.primary}
          disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存")}</Button></DialogActions>
    </DialogForm>
    {confirm && <Dialog variant={confirm === "delete" ? undefined : "discard"}
                        title={confirm === "delete" ? uiText("删除标签“{0}”？", [tag?.name]) : uiText("放弃未保存的标签名称？")}
                        onClose={() => setConfirm(null)} dialogRef={confirmation.ref} busy={action.busy}>
      {confirm === "delete" &&
        <p className={ui.description}>{uiText("此标签会从使用它的智能体、技能、插件等内容中移除。")}</p>}<MutationFeedback
      action={action}/>
      <DialogActions className={ui.footer}><DialogCancel className={ui.button}
                                                         disabled={action.busy}>{confirm === "discard" ? uiText("继续编辑") : uiText("取消")}</DialogCancel>
        <Button className={ui.danger} disabled={action.busy} onClick={() => {
          if (confirm === "discard") {
            finish(onClose);
          } else {
            void action.execute(async () => {
              await action.mutation.run(organizationPath(enterpriseId, `/tags/${encodeURIComponent(tag!.id)}`), {
                method: "DELETE",
                revision: tag!.revision
              });
              finish(() => onChanged(null, tag!.id));
            });
          }
        }}>{confirm === "discard" ? uiText("放弃修改") : uiText("确认删除")}</Button></DialogActions></Dialog>}
  </Dialog>;
}
