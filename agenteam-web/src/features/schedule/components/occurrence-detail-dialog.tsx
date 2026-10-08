"use client";

import {useEffect, useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Dialog} from "@/components/ui/dialog";
import {DetailHeading, DetailStatus} from "@/components/ui/detail-section";
import {IconHistory, IconRefresh, IconClock, IconUserCircle, IconInbox, IconUsers, IconArrowRight, IconAlertCircle} from "@/components/ui/icons";
import {ChannelIdentity} from "@/features/integration/components/channel-identity";
import {ChannelDeliveryStatus} from "@/features/notification/components/channel-delivery-status";
import {QueryState} from "@/components/ui/query-state";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {ChannelDeliveryDetailView} from "@/features/notification/components/channel-delivery-panel";
import {channelDeliveryBase} from "@/features/notification/types/channel-delivery";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {Occurrence, OccurrenceDetail} from "../types/schedule";
import {occurrenceActive, scheduleTime} from "../lib/schedule-display";
import {OccurrenceStatus, ScheduleContent} from "./schedule-presentation";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule-notification.module.css";
import presentation from "./schedule-presentation.module.css";

const inAppNames: Record<string, string> = {pending: "等待发送", delivered: "已送达", cancelled: "已停止", blocked: "未能发送", failed: "未能发送"};

export function OccurrenceDetailDialog({enterpriseId, occurrence, timezone, onClose, onChanged}: {
  enterpriseId: string; occurrence: Occurrence; timezone: string; onClose: () => void; onChanged: () => void;
}) {
  const t = useT();
  const [refresh, setRefresh] = useState(0);
  const [delivery, setDelivery] = useState<string | null>(null);
  const detail = useApiQuery<OccurrenceDetail>(organizationPath(enterpriseId,
    `/schedules/${encodeURIComponent(occurrence.scheduleId)}/occurrences/${encodeURIComponent(occurrence.id)}`), refresh);
  const value = detail.data?.occurrence;
  const working = Boolean(value && occurrenceActive(value) && !detail.loading && !detail.error);
  useEffect(() => {
    if (!working || delivery) {
      return;
    }
    const timer = window.setTimeout(() => setRefresh(previous => previous + 1), 2000);
    return () => window.clearTimeout(timer);
  }, [working, delivery, refresh]);
  const changed = () => {
    setRefresh(previous => previous + 1);
    onChanged();
  };
  return <Dialog title={t("本次执行详情")} icon={<IconHistory size={21}/>} onClose={onClose} wide>
    <AnimatedHeight preserveControlShadows>
      {delivery ? <ChannelDeliveryDetailView base={channelDeliveryBase(enterpriseId)} id={delivery} onChanged={changed} onBack={() => {
        setDelivery(null);
        changed();
      }}/> : <div className={ui.form}>
        <div className={presentation.overviewHead}><span className={presentation.metadata}><IconClock size={16}/>{scheduleTime(occurrence.scheduledFor, timezone, t.formatLocale)}</span>
          <Button className={ui.button} disabled={detail.loading} onClick={changed}><IconRefresh size={16}/>{t("刷新")}</Button></div>
        <QueryState {...detail} hasData={Boolean(value)} empty={t("本次执行暂不可查看。")}>
          {value && <>
            <div><OccurrenceStatus status={value.status}/></div>
            {value.reason && <p className={presentation.reason}><IconAlertCircle size={16}/>{value.reason}</p>}
            {value.snapshotOrigin === "legacy_unavailable" ? <p className={ui.description}>{t("此历史记录未保存当时的任务内容。")}</p>
              : <ScheduleContent notification={value.actionType === "notification.send"}
                title={value.actionType === "notification.send" ? String(value.actionSnapshot?.title ?? "") : undefined}
                body={String(value.actionType === "notification.send" ? value.actionSnapshot?.body ?? "" : value.actionSnapshot?.inputText ?? "")}/>}
            {Boolean(detail.data?.recipients.length) && <DetailHeading icon={<IconUsers size={19}/>} title={t("接收结果")}/>}
            <div className={styles.results}>{detail.data?.recipients.map(person => <article className={styles.recipientResult} key={person.userId}>
              <h3><IconUserCircle size={20}/>{person.name}</h3>
              <div className={styles.channelResult}><span className={presentation.metadata}><IconInbox size={18}/>{t("站内通知")}</span>
                <DetailStatus tone={person.inAppStatus === "delivered" ? "success" : ["blocked", "failed"].includes(person.inAppStatus) ? "danger" : "neutral"}>
                  {t(inAppNames[person.inAppStatus] ?? "未能发送")}</DetailStatus></div>
              {person.reason && <p className={presentation.reason}><IconAlertCircle size={16}/>{person.reason}</p>}
              {person.channels.map(channel => <div className={styles.channelResult} key={channel.id}>
                <div className={styles.channelIdentity}><ChannelIdentity name={channel.connectionName} providerName={channel.providerName} compact/>
                  {channel.errorSummary && <p className={styles.channelError}>{channel.errorSummary}</p>}</div>
                <div className={styles.channelActions}><ChannelDeliveryStatus status={channel.status}/>
                  <Button className={ui.button} onClick={() => setDelivery(channel.id)}>{t("查看发送详情")}<IconArrowRight size={15}/></Button></div>
              </div>)}
            </article>)}</div>
          </>}
        </QueryState>
      </div>}
    </AnimatedHeight>
  </Dialog>;
}
