"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {IconMessages} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {ReplyMode, ReplyPreference, TaskReplyMode} from "../types/reply-preferences";
import {ReplyModeChoices, ReplySource} from "./reply-mode-choices";
import ui from "@/components/ui/surface.module.css";
import styles from "./reply-preferences.module.css";

export function ReplyModeDialog({path, task, onClose, onSaved}: {path: string; task?: boolean; onClose: () => void; onSaved: () => void}) {
  const t = useT();
  const action = useFormAction();
  const query = useApiQuery<ReplyPreference | TaskReplyMode>(path);
  return <Dialog title={t(task ? "本任务的回复方式" : "我的默认回复方式")} icon={<IconMessages size={21}/>} onClose={onClose} busy={action.busy}>
    <QueryState {...query} hasData={Boolean(query.data)} empty={t("暂时无法读取回复方式。")}>
      {query.data && <ReplyModeForm key={`${query.data.revision}:${"conversation" in query.data ? query.data.conversation.revision : ""}`}
        path={path} value={query.data} onSaved={onSaved} reload={query.retry} action={action}/>}
    </QueryState>
  </Dialog>;
}

function ReplyModeForm({path, value, onSaved, reload, action}: {
  path: string; value: ReplyPreference | TaskReplyMode; onSaved: () => void; reload: () => void; action: ReturnType<typeof useFormAction>;
}) {
  const t = useT();
  const task = "conversation" in value;
  const source = task ? value.conversation : value;
  const [mode, setMode] = useState<ReplyMode>(value.outputVisibility);
  const [saveDefault, setSaveDefault] = useState(false);
  return <DialogForm className={styles.form} onSubmit={(event) => {
    event.preventDefault();
    void action.execute(async () => {
      await action.mutation.run(path, {method: "POST", body: {outputVisibility: mode, expectedRevision: value.revision,
        ...(task ? {saveAsDefault: saveDefault, expectedDefaultRevision: source.revision} : {})}});
      onSaved();
    }, t("回复方式已保存。"));
  }}>
    <ReplySource value={source}/>
    <ReplyModeChoices value={mode} onChange={(next) => {
      setMode(next);
      action.clearFieldError("outputVisibility");
    }} fullAllowed={source.fullAllowed} disabled={action.busy} error={action.fieldErrors.outputVisibility?.join(" ")}/>
    {task ? <label className={styles.defaultChoice}><Checkbox checked={saveDefault} disabled={action.busy} onCheckedChange={setSaveDefault}/>
      {t(source.kind === "direct" ? "设为我在此私聊的默认方式" : "设为我在本群的默认方式")}</label>
      : <p className={styles.hint}>{t("用于你在此会话新发起的任务。")}</p>}
    <MutationFeedback action={action} onReload={reload}/>
    <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
      <Button className={ui.primary} disabled={action.busy}>{t(action.busy ? "正在保存…" : "保存")}</Button></DialogActions>
  </DialogForm>;
}
