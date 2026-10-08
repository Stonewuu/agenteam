"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Textarea} from "@/components/ui/textarea";
import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import Link from "next/link";
import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useBlockNavigation} from "@/features/workspace/components/navigation-guard";
import type {Memory, MemoryContext, MemorySource} from "../types/memory";
import ui from "@/components/ui/surface.module.css";
import styles from "./memory.module.css";

export function MemoryEditor({enterpriseId, agentId, initial, source, onClose, onSaved, onReload}: {
  enterpriseId: string;
  agentId: string;
  initial: Memory | null;
  source?: MemorySource;
  onClose: () => void;
  onSaved: () => void;
  onReload: () => void;
}) {
  const uiText = useT();
  const root = organizationPath(enterpriseId, `/agents/${encodeURIComponent(agentId)}/memories`);
  const context = useApiQuery<MemoryContext>(initial ? null : `${root}/context${source ? `?sourceMessageId=${encodeURIComponent(source.sourceMessageId)}` : ""}`);
  const [content, setContent] = useState(initial?.content ?? source?.text ?? "");
  const [topic, setTopic] = useState(initial?.memoryKey ?? "");
  const [discard, setDiscard] = useState(false);
  const action = useFormAction();
  const dirty = content !== (initial?.content ?? "") || topic !== (initial?.memoryKey ?? "");
  useBlockNavigation(dirty, action.busy);
  const dialog = useDialogControl();
  const topics = context.data?.allowedTopics ?? [];
  const selected = topic || (topics.length === 1 ? topics[0] : "");
  const form = <form className={ui.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      if (Array.from(content).length > 500) {
        throw new Error(uiText("偏好最多 500 字，请保留之后仍需要使用的内容。"));
      }
      await action.mutation.run<Memory>(`${root}${initial ? `/${encodeURIComponent(initial.id)}` : ""}`, {
        method: initial ? "PUT" : "POST", revision: initial?.revision,
        body: {memoryKey: selected, content, sourceMessageId: initial ? null : source?.sourceMessageId ?? null}
      });
      dialog.close(onSaved);
    }, "");
  }}><Fieldset className={`${ui.form} ${styles.fields}`} disabled={action.busy}>
    {initial ? <div className={ui.field}><span>{uiText("主题")}</span><strong>{initial.memoryKey}</strong></div> :
      <label className={ui.field}><span>{uiText("主题")}</span><Select className={ui.select} required value={selected}
                                                                       aria-invalid={Boolean(action.fieldErrors.memoryKey?.length)}
                                                                       onChange={(event) => setTopic(event.target.value)}>
        <option value="">{uiText("请选择主题")}</option>
        {topics.map((value) => <option key={value} value={value}>{value}</option>)}</Select></label>}
    <label className={ui.field}><span>{uiText("偏好内容")}</span><Textarea className={ui.textarea} required rows={6}
                                                                           maxLength={500} value={content}
                                                                           aria-invalid={Boolean(action.fieldErrors.content?.length)}
                                                                           onChange={(event) => setContent(event.target.value)}/>
      {Array.from(content).length > 500 && <small>{uiText("当前内容超过 500 字，请删减后保存。")}</small>}</label>
    <p className={ui.description}>{uiText("请勿填写密码、密钥、证件或他人的敏感资料。")}</p>
    <MutationFeedback action={action} onReload={() => dialog.close(onReload)} showFieldErrors/>
    <div className={ui.footer}><DialogCancel className={ui.button}
                                             disabled={action.busy}>{uiText("取消")}</DialogCancel><Button
      className={ui.primary}
      disabled={action.busy}>{action.busy ? uiText("正在保存…") : initial ? uiText("保存修改") : uiText("保存为偏好")}</Button>
    </div>
  </Fieldset></form>;
  return <Dialog title={initial ? uiText("编辑偏好") : uiText("保存为偏好")} onClose={onClose} dialogRef={dialog.ref}
                 onRequestClose={() => {
                   if (dirty) {
                     setDiscard(true);
                     return false;
                   }
                   return true;
                 }} busy={action.busy}>
    {initial ? form : <QueryState {...context} hasData={Boolean(context.data)}
                                  empty={uiText("暂时无法读取可保存的主题。")}>{context.data?.canSave ? form : <>
      <p
        className={ui.description}>{context.data?.unavailableReason && uiText(context.data.unavailableReason)}</p>{context.data && !context.data.enabled &&
      <Link className={ui.button} href="/settings">{uiText("打开个人设置")}</Link>}
      <DialogActions className={ui.footer}><DialogCancel
        className={ui.button}>{uiText("返回")}</DialogCancel></DialogActions>
    </>}</QueryState>}
    {discard &&
      <Dialog variant="discard" title={uiText("放弃未保存的偏好？")} onClose={() => setDiscard(false)}><DialogActions
        className={ui.footer}><DialogCancel className={ui.button}>{uiText("继续编辑")}</DialogCancel><DialogCancel
        className={ui.danger} onClick={() => dialog.close(onClose)}>{uiText("放弃修改")}</DialogCancel></DialogActions></Dialog>}
  </Dialog>;
}
