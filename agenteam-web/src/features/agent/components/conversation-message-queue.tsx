"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconArrowUp, IconClock, IconList, IconTrash} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";

import styles from "./conversation-message-queue.module.css";

export type QueueListItem = {id: string; text: string; group?: string; recipient?: string; selected?: boolean};

export function ConversationMessageQueue({items, onChange}: {
  items: QueueListItem[];
  onChange: (id: string, prioritize: boolean) => Promise<void>;
}) {
  const t = useT();
  const [busy, setBusy] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  if (!items.length) {
    return null;
  }

  async function change(id: string, prioritize: boolean) {
    setBusy(id);
    try {
      await onChange(id, prioritize);
    } finally {
      setBusy(null);
    }
  }

  return <section className={styles.panel} aria-label={t("待处理消息")}>
    <div className={styles.heading}><IconClock size={14}/>{t("待处理消息")}<span>{items.length}</span></div>
    <ol className={styles.list}>{items.map((item, index) => <li key={item.id} className={styles.row}>
      <IconList size={15}/>
      {item.recipient && <span className={styles.recipient} title={item.recipient}>@{item.recipient}</span>}
      <Button type="button" className={styles.preview} data-expanded={expanded === item.id}
        aria-expanded={expanded === item.id} onClick={() => setExpanded(expanded === item.id ? null : item.id)}
        title={item.text}>{item.text}</Button>
      {item.selected || index === 0 || items[index - 1].group !== item.group || items[index - 1].selected
        ? <span className={styles.next}>{t(item.selected ? "正在接续" : "下一条")}</span>
        : <Button type="button" className={styles.priority} title={t("在当前步骤结束后优先处理")}
            aria-label={t("优先处理第 {0} 条消息", [index + 1])} disabled={busy !== null}
            onClick={() => void change(item.id, true)}><IconArrowUp size={15}/>{t("优先")}</Button>}
      <Button type="button" className={styles.withdraw} disabled={busy !== null || item.selected}
        aria-label={t("撤回第 {0} 条消息", [index + 1])} title={t("撤回消息")}
        onClick={() => void change(item.id, false)}><IconTrash size={16}/></Button>
    </li>)}</ol>
  </section>;
}
