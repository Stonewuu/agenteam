"use client";

import {useEffect, useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {DetailHeading, DetailStatus} from "@/components/ui/detail-section";
import {IconArrowLeft, IconArrowRight, IconRefresh, IconUserCircle, IconClock, IconHistory, IconAlertCircle, IconPlug} from "@/components/ui/icons";
import {ChannelIdentity} from "@/features/integration/components/channel-identity";
import {ChannelDeliveryStatus} from "./channel-delivery-status";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {channelDeliveryBase, deliveryInProgress, type ChannelDelivery, type ChannelDeliveryDetail} from "../types/channel-delivery";
import styles from "./channel-delivery.module.css";
import ui from "@/components/ui/surface.module.css";

export function ChannelDeliveryPanel({enterpriseId, system = false, connectionId, notificationId, initialDeliveryId, showHeading = false}: {
  enterpriseId: string; system?: boolean; connectionId?: string; notificationId?: string; initialDeliveryId?: string; showHeading?: boolean;
}) {
  const t = useT();
  const base = channelDeliveryBase(enterpriseId, system);
  const path = connectionId ? `${base}/integrations/${encodeURIComponent(connectionId)}/deliveries`
    : `${base}/notifications/${encodeURIComponent(notificationId ?? "")}/deliveries`;
  const [selected, setSelected] = useState(initialDeliveryId ?? null);
  const [refresh, setRefresh] = useState(0);
  const list = useApiPage<ChannelDelivery>(path, refresh, 0, 20, !selected);
  const working = list.data?.items.some(deliveryInProgress) && !list.error && !list.loading;
  useEffect(() => {
    if (!working || selected) {
      return;
    }
    const timer = window.setTimeout(() => setRefresh((value) => value + 1), 3000);
    return () => window.clearTimeout(timer);
  }, [working, selected, refresh]);
  const changed = () => setRefresh((value) => value + 1);

  return <AnimatedHeight preserveControlShadows>
    {selected ? <ChannelDeliveryDetailView key={selected} base={base} id={selected} showHeading={showHeading} onBack={() => {
      setSelected(null);
      changed();
    }}/> : <div className={styles.content}>
      <div className={styles.panelHeader}>
        {showHeading && <DetailHeading level="h2" icon={<IconPlug size={20}/>} title={t("渠道发送结果")}/>}
        <Button className={ui.button} aria-label={t("刷新发送记录")} disabled={list.loading} onClick={changed}><IconRefresh size={15}/>{t("刷新")}</Button>
      </div>
      <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={t("暂无外部渠道发送记录。")}>
        <div className={styles.list}>{list.data?.items.map((value) => <article className={styles.record} key={value.id}>
          <div className={styles.heading}><ChannelIdentity name={value.connectionName} providerName={value.providerName} compact/>
            <ChannelDeliveryStatus status={value.status}/></div>
          <p className={styles.meta}><span><IconUserCircle size={16}/>{value.recipientName}</span><span><IconClock size={15}/><EnterpriseDateTime value={value.createdAt}/></span></p>
          {value.errorSummary && <p className={styles.reason}><IconAlertCircle size={16}/>{value.errorSummary}</p>}
          <div className={styles.recordFooter}><Button className={ui.button} onClick={() => setSelected(value.id)}>{t("查看发送详情")}<IconArrowRight size={15}/></Button></div>
        </article>)}</div>
      </QueryState>
      <Pagination {...list} hasMore={list.data?.hasMore}/>
    </div>}
  </AnimatedHeight>;
}

export function ChannelDeliveryDetailView({base, id, onBack, onChanged, showHeading = false}: {base: string; id: string; onBack: () => void; onChanged?: () => void; showHeading?: boolean}) {
  const t = useT();
  const [refresh, setRefresh] = useState(0);
  const [acknowledged, setAcknowledged] = useState(false);
  const detail = useApiQuery<ChannelDeliveryDetail>(`${base}/notification-deliveries/${encodeURIComponent(id)}`, refresh);
  const action = useFormAction();
  const value = detail.data?.delivery;
  const working = Boolean(value && deliveryInProgress(value) && !detail.loading && !detail.error);
  useEffect(() => {
    if (!working) {
      return;
    }
    const timer = window.setTimeout(() => setRefresh((current) => current + 1), 2000);
    return () => window.clearTimeout(timer);
  }, [working, refresh]);
  const outcomes: Record<string, string> = {started: "正在请求平台", accepted: "平台已接受", retryable_failure: "本次未发送成功",
    permanent_failure: "平台拒绝发送", unknown: "结果无法确认"};
  return <div className={styles.content}>
    <div className={styles.panelHeader}><div className={styles.panelNavigation}>
      <Button className={showHeading ? "icon-button" : ui.button} aria-label={t("返回发送记录")} onClick={onBack}><IconArrowLeft size={18}/>{!showHeading && t("返回发送记录")}</Button>
      {showHeading && <h2>{t("发送详情")}</h2>}</div>
      <Button className={ui.button} disabled={detail.loading} onClick={() => setRefresh((current) => current + 1)}><IconRefresh size={15}/>{t("刷新")}</Button></div>
    <QueryState {...detail} hasData={Boolean(value)} empty={t("发送记录暂不可用。")}>
      {value && <>
        <section className={styles.record}><div className={styles.heading}>
          <ChannelIdentity name={value.connectionName} providerName={value.providerName} detail={value.recipientName}/>
          <ChannelDeliveryStatus status={value.status}/></div>
          {value.errorSummary && <p className={styles.reason}><IconAlertCircle size={16}/>{value.errorSummary}</p>}
          <dl className={styles.facts}><div><dt>{t("创建时间")}</dt><dd><EnterpriseDateTime value={value.createdAt}/></dd></div>
            {value.acceptedAt && <div><dt>{t("平台接受时间")}</dt><dd><EnterpriseDateTime value={value.acceptedAt}/></dd></div>}
            {value.nextAttemptAt && <div><dt>{t("下次尝试时间")}</dt><dd><EnterpriseDateTime value={value.nextAttemptAt}/></dd></div>}</dl>
        </section>
        <DetailHeading icon={<IconHistory size={19}/>} title={t("发送过程")}/>
        <ol className={styles.timeline}>{detail.data?.attempts.map((attempt) => <li className={styles.attempt} key={attempt.number}>
          <span className={styles.attemptNumber}>{attempt.number}</span><article>
            <div className={styles.heading}><strong>{t("第 {0} 次发送", [String(attempt.number)])}</strong>
              <DetailStatus tone={attempt.outcome === "accepted" ? "success" : attempt.outcome === "permanent_failure" ? "danger" : attempt.outcome === "started" ? "neutral" : "warning"}>
                {t(outcomes[attempt.outcome] ?? "结果无法确认")}</DetailStatus></div>
            <p className={styles.meta}><IconClock size={14}/><EnterpriseDateTime value={attempt.startedAt}/></p>
            {attempt.summary && <p className={styles.attemptSummary}>{attempt.summary}</p>}
          </article>
        </li>)}</ol>
        {value.canRetry && <div className={styles.retry}>
          {value.duplicateConfirmationRequired && <label className={styles.confirm}><Checkbox checked={acknowledged}
            disabled={action.busy} onCheckedChange={(checked) => setAcknowledged(checked === true)}/>
            <span>{t("此前请求可能已被接受，我确认再次尝试可能产生重复通知。")}</span></label>}
          <Button className={ui.button} disabled={action.busy || value.duplicateConfirmationRequired && !acknowledged} onClick={() => void action.execute(async () => {
            await action.mutation.run(`${base}/notification-deliveries/${encodeURIComponent(id)}/retry`, {method: "POST", revision: value.revision,
              body: {confirmMayDuplicate: acknowledged}});
            setAcknowledged(false);
            setRefresh((current) => current + 1);
            onChanged?.();
          }, t("已安排再次尝试。"))}><IconRefresh size={16}/>{t("再次尝试发送")}</Button>
        </div>}
      </>}
    </QueryState>
    <MutationFeedback action={action} onReload={() => setRefresh((current) => current + 1)}/>
  </div>;
}
