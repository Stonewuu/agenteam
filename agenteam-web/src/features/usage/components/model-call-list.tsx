"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconArrowDown, IconArrowUp, IconChevronRight, IconClock, IconDatabase, IconUser} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {ModelCallDetails} from "./model-call-details";
import {usageAmount, usagePurposes, tokenCount, priceReasons} from "../lib/model-usage-display";
import type {ModelCall, UsageToken} from "../types/model-usage";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-call-table.module.css";

export function ModelCallList({calls}: {calls: ModelCall[]}) {
  const t = useT();
  const [selected, setSelected] = useState<string | null>(null);
  const current = calls.find((value) => value.id === selected);
  const states: Record<string, string> = {registered: "等待请求", in_flight: "调用中", failed: "调用失败", cancelled: "已取消", interrupted: "调用中断"};
  return <><div className={styles.scroll}>
    <table className={styles.table}>
      <caption className={styles.visuallyHidden}>{t("模型调用明细")}</caption>
      <thead><tr><th scope="col">{t("模型 / 用途")}</th><th scope="col">{t("用户")}</th><th scope="col">{t("调用时间")}</th>
        <th scope="col" className={styles.number}>{t("输入 Token")}</th><th scope="col" className={styles.number}>{t("输出 Token")}</th>
        <th scope="col" className={styles.number}>{t("缓存读取")}</th><th scope="col" className={styles.number}>{t("费用 / 计价状态")}</th>
        <th scope="col"><span className={styles.visuallyHidden}>{t("操作")}</span></th></tr></thead>
      <tbody>{calls.map((call) => <tr key={call.id}>
        <th scope="row" className={styles.model}>
          <strong>{call.modelProfileName || call.modelName}</strong>
          <small title={call.modelName}>{call.modelProfileName && call.modelProfileName !== call.modelName ? `${call.modelName} · ` : ""}{call.providerName}</small>
          <span className={styles.purpose}>{t(usagePurposes[call.purpose] ?? "模型调用")}</span>
        </th>
        <td className={styles.user} data-label={t("用户")}><IconUser className={styles.metaIcon} size={14}/><span>{call.userName || t("未提供姓名")}</span></td>
        <td className={styles.time} data-label={t("调用时间")}>
          <IconClock className={styles.metaIcon} size={14}/>
          <time dateTime={call.dispatchedAt}>{new Date(call.dispatchedAt).toLocaleDateString(t.formatLocale)}<small>
            {new Date(call.dispatchedAt).toLocaleTimeString(t.formatLocale, {hour12: false})}</small></time>
          {states[call.callStatus] && <small className={styles.callState}>{t(states[call.callStatus])}</small>}
        </td>
        <td className={`${styles.number} ${styles.metric}`} data-meter="input" data-label={t("输入 Token")}>
          <span className={styles.mobileLabel}><IconArrowDown size={14}/>{t("输入 Token")}</span><TokenValue token={call.tokens.input}/></td>
        <td className={`${styles.number} ${styles.metric}`} data-meter="output" data-label={t("输出 Token")}>
          <span className={styles.mobileLabel}><IconArrowUp size={14}/>{t("输出 Token")}</span><TokenValue token={call.tokens.output}/></td>
        <td className={`${styles.number} ${styles.metric}`} data-meter="cache_read" data-label={t("缓存读取")}>
          <span className={styles.mobileLabel}><IconDatabase size={14}/>{t("缓存读取")}</span><TokenValue token={call.tokens.cache_read}/></td>
        <td className={`${styles.number} ${styles.cost}`} data-label={t("费用")}><CallAmount call={call}/></td>
        <td className={styles.action}><Button type="button" className={ui.button}
          aria-label={t("查看 {0} 的调用详情", [call.modelProfileName || call.modelName])} onClick={() => setSelected(call.id)}>
          {t("详情")}<IconChevronRight size={14}/></Button></td>
      </tr>)}</tbody>
    </table>
  </div>{current && <ModelCallDetails call={current} onClose={() => setSelected(null)}/>}</>;
}

function TokenValue({token}: {token: UsageToken | undefined}) {
  const t = useT();
  return <span title={token?.state === "not_applicable" ? t("不适用") : undefined}>
    {tokenCount(token?.value, t.formatLocale) ?? (token?.state === "not_applicable" ? "—" : t("未返回"))}
  </span>;
}

function CallAmount({call}: {call: ModelCall}) {
  const t = useT();
  const label = call.priceStatus === "priced" ? t("已计价") : call.priceStatus === "partial" ? t("部分计价")
    : call.priceStatus === "pending" ? t("金额待计算") : call.reasonCode === "PRICE_NOT_CONFIGURED" ? t("未配置价格") : t("未计价");
  const amount = call.totalAmount ?? call.knownAmount;
  return <div className={styles.amount}>
    <strong title={amount ?? undefined}>{amount === null ? "—" : usageAmount(amount, t.formatLocale)}
      {amount !== null && <span>{call.currency}</span>}</strong>
    <span className={styles.priceState} data-state={call.priceStatus}
      title={call.reasonCode && priceReasons[call.reasonCode] ? t(priceReasons[call.reasonCode]) : undefined}>{label}</span>
  </div>;
}
