"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel, useDialogControl} from "@/components/ui/dialog";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {Conversation} from "../types/execution";
import {conversationPath} from "../api/conversation-api";
import ui from "@/components/ui/surface.module.css";

export function ConversationManagementDialog({enterprise, conversation, onClose, onChanged, initialAction = "rename"}: {
  enterprise: string;
  conversation: Conversation;
  onClose: () => void;
  onChanged: (action: "updated" | "deleted" | "restored", value?: Conversation) => void;
  initialAction?: "rename" | "delete";
}) {
  const uiText = useT();
  const action = useFormAction();
  const [title, setTitle] = useState(conversation.title);
  const [confirmDelete, setConfirmDelete] = useState(initialAction === "delete");
  const path = conversationPath(enterprise, conversation.id);
  const dialog = useDialogControl(), confirmation = useDialogControl();
  const finish = (complete: () => void) => {
    confirmation.close();
    dialog.close(complete);
  };
  const deleted = conversation.status === "deleted";
  const update = (body: object) => action.execute(async () => {
    const updated = await action.mutation.run<Conversation>(path, {
      method: "PATCH",
      revision: conversation.revision,
      body
    });
    finish(() => {
      onChanged("updated", updated);
      onClose();
    });
  });
  return <Dialog title={deleted ? uiText("恢复对话") : uiText("管理对话")} onClose={onClose} dialogRef={dialog.ref}
                 busy={action.busy}>
    <div className={ui.form}>
      {deleted ? <><p
        className={ui.description}>{uiText("恢复“")}{conversation.title}{uiText("”后即可查看原有消息。")}</p><Button
        className={ui.primary} disabled={action.busy} onClick={() => void action.execute(async () => {
        const restored = await action.mutation.run<Conversation>(`${path}/restore`, {
          method: "POST",
          revision: conversation.revision
        });
        finish(() => {
          onChanged("restored", restored);
          onClose();
        });
      })}>{uiText("恢复对话")}</Button></> : <>
        <form className={ui.form} onSubmit={(event) => {
          event.preventDefault();
          void update({title: title.trim()});
        }}>
          <label className={ui.field}><span>{uiText("对话名称")}</span><Input className={ui.input} value={title}
                                                                              maxLength={100}
                                                                              onChange={(event) => setTitle(event.target.value)}/></label>
          <Button className={ui.primary}
                  disabled={action.busy || !title.trim() || title.trim() === conversation.title}>{uiText("保存名称")}</Button>
        </form>
        <div className={ui.actions}>
          <Button className={ui.button} disabled={action.busy}
                  onClick={() => void update({favorite: !conversation.favorite})}>{conversation.favorite ? uiText("取消收藏") : uiText("收藏")}</Button>
          <Button className={ui.button} disabled={action.busy || conversation.activeRunId !== null}
                  onClick={() => void update({status: conversation.status === "archived" ? "active" : "archived"})}>{conversation.status === "archived" ? uiText("取消归档") : uiText("归档")}</Button>
          <Button className={ui.danger} disabled={action.busy || conversation.activeRunId !== null}
                  onClick={() => setConfirmDelete(true)}>{uiText("删除")}</Button>
        </div>
        {conversation.activeRunId && <p className={ui.description}>{uiText("当前任务结束后可以归档或删除。")}</p>}
      </>}
      <MutationFeedback action={action} onReload={() => {
        finish(() => {
          onChanged("updated");
          onClose();
        });
      }}/>
    </div>
    {confirmDelete &&
      <Dialog title={uiText("删除这段对话？")} onClose={() => setConfirmDelete(false)} dialogRef={confirmation.ref}
              busy={action.busy}>
        <p className={ui.description}>{uiText("删除后可在三十天内恢复。")}</p>
        <MutationFeedback action={action} onReload={() => {
          finish(() => {
            onChanged("updated");
            onClose();
          });
        }}/><DialogActions className={ui.footer}>
        <DialogCancel className={ui.button} disabled={action.busy}>{uiText("取消")}</DialogCancel>
        <Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
          await action.mutation.run(path, {method: "DELETE", revision: conversation.revision});
          finish(() => {
            onChanged("deleted");
            onClose();
          });
        })}>{uiText("删除对话")}</Button></DialogActions>
      </Dialog>}
  </Dialog>;
}
