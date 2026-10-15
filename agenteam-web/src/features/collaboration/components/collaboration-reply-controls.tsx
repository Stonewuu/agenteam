"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconPencil, IconSend} from "@/components/ui/icons";
import {ReplyModeDialog} from "@/features/chat/components/reply-mode-dialog";
import {useT} from "@/lib/i18n/locale-provider";
import type {CollaborationDetail} from "../types/collaboration";
import {CollaborationResendDialog} from "./collaboration-resend-dialog";
import ui from "@/components/ui/surface.module.css";
import styles from "./collaboration.module.css";
import reply from "@/features/chat/components/reply-preferences.module.css";

export function CollaborationReplyControls({path, detail, initiallyOpen, onChanged}: {
  path: string; detail: CollaborationDetail; initiallyOpen?: boolean; onChanged: () => void;
}) {
  const t = useT();
  const [editing, setEditing] = useState(Boolean(initiallyOpen));
  const [resending, setResending] = useState(false);
  const full = detail.fullOutputAllowed && detail.task.outputVisibility === "chat_full";
  return <><div className={styles.fact}>
    <dt className={reply.controlHeading}><span><IconSend size={15}/>{t("回复方式")}</span>
      <Button className={ui.button} onClick={() => setEditing(true)}><IconPencil size={15}/>{t("修改")}</Button></dt>
    <dd className={reply.controls}><span>{t(full ? "直接回复内容" : "仅发送链接")}</span>
      {!detail.fullOutputAllowed && <small className={reply.restriction}>{t("本群已设为仅发送链接")}</small>}
      {detail.task.status === "completed" && detail.artifacts.length > 0 && <Button className={ui.button} onClick={() => setResending(true)}>
        <IconSend size={16}/>{t("发送已有结果")}</Button>}
    </dd>
  </div>
    {editing && <ReplyModeDialog task path={`${path}/reply-mode`} onClose={() => setEditing(false)} onSaved={() => {
      setEditing(false);
      onChanged();
    }}/>}
    {resending && <CollaborationResendDialog path={path} detail={detail} onClose={() => setResending(false)} onSent={() => {
      setResending(false);
      onChanged();
    }}/>}
  </>;
}
