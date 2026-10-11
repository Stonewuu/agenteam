"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {IconAlertCircle, IconArrowDown, IconArrowUp, IconChartBar, IconClock, IconDatabase, IconStack, IconWallet} from "@/components/ui/icons";
import {tokenCount, usageAmount} from "../lib/model-usage-display";
import type {ModelUsageTotals} from "../types/model-usage";
import styles from "./model-usage.module.css";

export function ModelUsageSummaryCards({totals}: {totals: ModelUsageTotals}) {
  const t = useT();
  const metrics = [
    {key: "input", label: t("全部输入"), Icon: IconArrowDown, tone: "input"},
    {key: "output", label: t("输出"), Icon: IconArrowUp, tone: "output"},
    {key: "cache_read", label: t("缓存读取"), Icon: IconDatabase, tone: "cache"},
    {key: "cache_write", label: t("缓存创建"), Icon: IconStack, tone: "cache"},
  ].filter(({key}) => !(totals.calls !== "0" && totals.tokens[key]?.notApplicableCalls === totals.calls));
  return <div className={styles.stack}>
    <div className={styles.overviewHeading}><h2><IconWallet size={20}/>{t("费用概览")}</h2>
      <span><IconChartBar size={16}/>{t("共 {0} 次模型调用", [tokenCount(totals.calls, t.formatLocale) ?? "0"])}</span></div>
    <div className={styles.money}>{totals.amounts.map((value) => <section key={value.currency} className={styles.moneyCard}>
      <span className={styles.moneyIcon}><IconWallet size={24} variant="Bulk"/></span>
      <div className={styles.moneyCopy}><span>{value.completeAmount !== null ? t("已计价费用") : t("已确认部分")}</span>
        <div className={styles.moneyValue}><strong title={value.completeAmount ?? value.partialAmount ?? undefined}>
          {value.completeAmount !== null ? usageAmount(value.completeAmount, t.formatLocale) : value.partialAmount !== null ? usageAmount(value.partialAmount, t.formatLocale) : "—"}</strong>
          <span>{value.currency}</span></div>
        {value.completeAmount !== null && value.partialAmount !== null && <small title={value.partialAmount}>
          {t("另有已确认部分：{0} {1}", [usageAmount(value.partialAmount, t.formatLocale), value.currency])}</small>}
      </div>
      <span className={styles.moneyCount}>{t("{0} 次调用已完整计价", [tokenCount(value.completeCalls, t.formatLocale) ?? "0"])}</span>
    </section>)}
      {totals.amounts.length === 0 && <section className={styles.moneyCard}><span className={styles.moneyIcon}><IconWallet size={24} variant="Bulk"/></span>
        <div className={styles.moneyCopy}><span>{t("已计价费用")}</span><strong className={styles.noAmount}>{t("暂无已确认金额")}</strong></div></section>}
    </div>
    <div className={styles.tokens}>{metrics.map(({key, label, Icon, tone}) => {
      const value = totals.tokens[key];
      return <div key={key} className={styles.metric}><div className={styles.metricHeading}><span className={styles.metricIcon} data-tone={tone}><Icon size={20}/></span><span>{label}</span></div>
        <div className={styles.metricValue}><strong>{tokenCount(value?.value, t.formatLocale)
        ?? (value?.notApplicableCalls === totals.calls && totals.calls !== "0" ? t("不适用") : t("未返回"))}</strong>
          {value?.value !== null && value?.value !== undefined && <span>Token</span>}</div>
        {value && BigInt(value.knownCalls) + BigInt(value.notApplicableCalls) < BigInt(totals.calls)
          && <small>{t("{0} 次调用已返回", [tokenCount(value.knownCalls, t.formatLocale) ?? "0"])}</small>}</div>;
    })}</div>
    <p className={styles.usageHint}><IconAlertCircle size={15}/>{t("缓存属于输入子项，思考属于输出子项。")}</p>
    {(totals.incompleteUsageCalls !== "0" || totals.unpricedCalls !== "0" || totals.pendingCalls !== "0") && <div className={styles.notices}>
      {totals.incompleteUsageCalls !== "0" && <p><IconAlertCircle size={17}/>{t("有 {0} 次调用未返回完整用量。", [tokenCount(totals.incompleteUsageCalls, t.formatLocale) ?? "0"])}</p>}
      {totals.unpricedCalls !== "0" && <p><IconWallet size={17}/>{t("有 {0} 次调用尚未完整计价，可在明细中查看原因。", [tokenCount(totals.unpricedCalls, t.formatLocale) ?? "0"])}</p>}
      {totals.pendingCalls !== "0" && <p><IconClock size={17}/>{t("有 {0} 次调用尚未完成金额计算。", [tokenCount(totals.pendingCalls, t.formatLocale) ?? "0"])}</p>}
    </div>}
  </div>;
}
