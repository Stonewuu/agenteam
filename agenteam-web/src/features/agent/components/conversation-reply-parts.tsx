"use client";

import {type ReactNode, useEffect, useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {IconCheck, IconCopy} from "@/components/ui/icons";
import {toast} from "@/components/ui/toast";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./conversation-controls.module.css";

export function ConversationReplyAuthor({avatar, name, createdAt, children}: {avatar: ReactNode; name: string; createdAt: string; children?: ReactNode}) {
  return <div className={styles.messageMeta}><span className={styles.messageAvatar} aria-hidden="true">{avatar}</span>
    <span>{name}</span>{children}<EnterpriseDateTime value={createdAt} compact/></div>;
}

export function CopyReplyButton({text, messageId}: {text: string; messageId: string}) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (timer.current) {
      clearTimeout(timer.current);
    }
  }, []);
  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      if (timer.current) {
        clearTimeout(timer.current);
      }
      setCopied(true);
      timer.current = setTimeout(() => setCopied(false), 1800);
      toast.success(t("回复已复制。"));
    } catch (failure) {
      console.error("复制对话回复失败", {messageId, error: failure});
      setCopied(false);
      toast.error(t("暂时无法复制，请选择文字后复制。"));
    }
  }
  return <Button type="button" aria-label={copied ? t("已复制") : t("复制回复")} title={copied ? t("已复制") : t("复制回复")}
    data-copied={copied} onClick={() => void copy()}>{copied ? <IconCheck size={18}/> : <IconCopy size={18}/>}</Button>;
}
