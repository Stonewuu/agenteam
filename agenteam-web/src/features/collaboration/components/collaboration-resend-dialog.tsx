"use client";

import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {IconSend} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {ReplySource} from "@/features/chat/components/reply-mode-choices";
import type {TaskReplyMode} from "@/features/chat/types/reply-preferences";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {useCollaborationAction} from "../hooks/use-collaboration-action";
import type {CollaborationDetail} from "../types/collaboration";
import ui from "@/components/ui/surface.module.css";
import styles from "@/features/chat/components/reply-preferences.module.css";

export function CollaborationResendDialog({path, detail, onClose, onSent}: {
  path: string; detail: CollaborationDetail; onClose: () => void; onSent: () => void;
}) {
  const t = useT();
  const query = useApiQuery<TaskReplyMode>(`${path}/reply-mode`);
  const action = useCollaborationAction();
  return <Dialog title={t("发送已有结果")} icon={<IconSend size={21}/>} onClose={onClose} busy={action.busy}>
    <QueryState {...query} hasData={Boolean(query.data)} empty={t("暂时无法读取回复方式。")}>
      {query.data && <DialogForm className={styles.form} onSubmit={(event) => {
        event.preventDefault();
        void action.execute(async () => {
          await action.submit(`${path}/resend-result`, {expectedRevision: detail.task.revision, expectedReplyModeRevision: query.data!.revision});
          onSent();
        }, t("已加入发送队列，可在消息回传中查看进度。"));
      }}>
        <ReplySource value={query.data.conversation}/>
        <div className={styles.confirm}><strong>{detail.task.shortCode}</strong>
          <p>{t(query.data.outputVisibility === "chat_full" ? "将本任务已有的回答和成果附件发送到原会话。" : "将本任务的查看链接发送到原会话。")}</p>
          <p className={styles.hint}>{t("此次操作不会重新执行任务。")}</p></div>
        {!query.data.canSendResult && <p role="alert">{t("任务完成后才能发送已有结果。")}</p>}
        <MutationFeedback action={action} onReload={query.retry}/>
        <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
          <Button className={ui.primary} disabled={action.busy || !query.data.canSendResult}><IconSend size={16}/>{t(action.busy ? "正在提交…" : "确认发送")}</Button></DialogActions>
      </DialogForm>}
    </QueryState>
  </Dialog>;
}
