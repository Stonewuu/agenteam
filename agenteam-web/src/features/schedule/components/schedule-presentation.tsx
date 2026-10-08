"use client";

import {DetailSection, DetailStatus} from "@/components/ui/detail-section";
import {IconAlertCircle, IconBell, IconCheck, IconClock, IconFileText, IconInbox, IconPlug, IconUserCircle, IconUsers} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {occurrenceNames} from "../lib/schedule-display";
import type {Occurrence, ScheduleRecipient} from "../types/schedule";
import styles from "./schedule-presentation.module.css";

export function ScheduleRecipients({recipients}: {recipients: ScheduleRecipient[]}) {
  const t = useT();
  return <DetailSection title={t("接收人和渠道")} icon={<IconUsers size={19}/>}>
    <div className={styles.recipients}>{recipients.map(person => <div className={styles.recipient} key={person.userId}>
      <span className={styles.personIcon}><IconUserCircle size={21}/></span>
      <div><strong>{person.name}</strong><div className={styles.channels}>
        <span><IconInbox size={14}/>{t("站内通知")}</span>
        {person.channels.map(channel => <span key={channel.connectionId}><IconPlug size={14}/>{channel.name}
          {channel.providerName && <small>{channel.providerName}</small>}</span>)}
      </div></div>
    </div>)}</div>
  </DetailSection>;
}

export function ScheduleContent({notification, title, body}: {notification: boolean; title?: string; body: string}) {
  const t = useT();
  return <DetailSection title={notification ? t("通知内容") : t("任务内容")} icon={notification ? <IconBell size={19}/> : <IconFileText size={19}/>}>
    <div className={styles.message}>{title && <h3>{title}</h3>}{body && <div>{body}</div>}</div>
  </DetailSection>;
}

export function OccurrenceStatus({status, stopping = false}: {status: Occurrence["status"]; stopping?: boolean}) {
  const t = useT();
  const successful = status === "completed";
  const failed = status === "failed" || status === "blocked";
  const warning = ["partially_failed", "unknown", "missed", "waiting_approval"].includes(status);
  const active = ["queued", "running", "waiting_approval"].includes(status);
  const Icon = successful ? IconCheck : failed || warning ? IconAlertCircle : IconClock;
  return <DetailStatus tone={successful ? "success" : failed ? "danger" : warning ? "warning" : active ? "accent" : "neutral"}
    icon={<Icon size={14}/>}>{stopping ? t("正在停止") : t(occurrenceNames[status])}</DetailStatus>;
}
