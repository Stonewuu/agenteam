"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {IconAlertCircle, IconArrowDown, IconArrowUp, IconChartBar, IconWallet} from "@/components/ui/icons";
import {tokenCount, usageAmount} from "../lib/model-usage-display";
import type {ModelUsageTotals} from "../types/model-usage";
import styles from "./model-usage-overview.module.css";

export function ModelUsageSummaryCards({totals}: {totals: ModelUsageTotals}) {
  const t = useT();
  const count = (value: string) => tokenCount(value, t.formatLocale) ?? "0";
  const priced = totals.amounts.reduce((sum, amount) => sum + BigInt(amount.completeCalls), BigInt(0)).toString();
  return <section aria-label={t("用量概览")} className={styles.overview}>
    <div className={styles.grid}>
      <div className={`${styles.card} ${styles.cost}`}>
        <h2><IconWallet size={18}/>{t("已计价费用")}</h2>
        {totals.amounts.length === 0 ? <p className={styles.noAmount}>{t("暂无已确认金额")}</p>
          : totals.amounts.map((amount) => <div key={amount.currency} className={styles.currency}>
            <div className={styles.value}><strong title={amount.completeAmount ?? undefined}>
              {amount.completeAmount === null ? "—" : usageAmount(amount.completeAmount, t.formatLocale)}
            </strong><span>{amount.currency}</span></div>
            {amount.partialAmount !== null && <p title={amount.partialAmount}>{t("另有已确认部分：{0} {1}", [usageAmount(amount.partialAmount, t.formatLocale), amount.currency])}</p>}
          </div>)}
        <p className={styles.caption}>{t("仅汇总已完整计价的调用")}</p>
      </div>
      <div className={styles.card}>
        <h2><IconChartBar size={18}/>{t("模型调用")}</h2>
        <div className={styles.value}><strong>{count(totals.calls)}</strong><span>{t("次")}</span></div>
        <dl className={styles.breakdown}>
          <div><dt>{t("已完整计价")}</dt><dd>{t("{0} 次", [count(priced)])}</dd></div>
          {totals.unpricedCalls !== "0" && <div data-warning><dt>{t("未完整计价")}</dt><dd>{t("{0} 次", [count(totals.unpricedCalls)])}</dd></div>}
          {totals.pendingCalls !== "0" && <div><dt>{t("金额待计算")}</dt><dd>{t("{0} 次", [count(totals.pendingCalls)])}</dd></div>}
        </dl>
      </div>
      <div className={styles.card}>
        <h2><IconArrowDown size={18}/>{t("全部输入")}</h2>
        <UsageValue totals={totals} meter="input"/>
        <dl className={styles.breakdown}>
          <UsagePart totals={totals} meter="input_uncached" label={t("普通输入")}/>
          <UsagePart totals={totals} meter="cache_read" label={t("其中缓存读取")}/>
          <UsagePart totals={totals} meter="cache_write" label={t("其中缓存创建")}/>
          <UsagePart totals={totals} meter="tool_use_prompt" label={t("其中工具输入")}/>
        </dl>
      </div>
      <div className={styles.card}>
        <h2><IconArrowUp size={18}/>{t("输出")}</h2>
        <UsageValue totals={totals} meter="output"/>
        <dl className={styles.breakdown}><UsagePart totals={totals} meter="reasoning" label={t("其中思考")}/></dl>
      </div>
    </div>
    {totals.incompleteUsageCalls !== "0" && <p className={styles.notice}><IconAlertCircle size={16}/>
      {t("有 {0} 次调用未返回完整用量，统计仅包含已返回部分。", [count(totals.incompleteUsageCalls)])}</p>}
  </section>;
}

function UsageValue({totals, meter}: {totals: ModelUsageTotals; meter: string}) {
  const t = useT();
  const value = totals.tokens[meter];
  const missing = value && BigInt(value.knownCalls) + BigInt(value.notApplicableCalls) < BigInt(totals.calls);
  return <><div className={styles.value}><strong>{tokenCount(value?.value, t.formatLocale) ?? "—"}</strong><span>Token</span></div>
    {missing && <p className={styles.caption}>{t("{0} 次调用已返回", [tokenCount(value.knownCalls, t.formatLocale) ?? "0"])}</p>}</>;
}

function UsagePart({totals, meter, label}: {totals: ModelUsageTotals; meter: string; label: string}) {
  const t = useT();
  const value = totals.tokens[meter];
  if (!value || totals.calls === "0" || value.notApplicableCalls === totals.calls) {
    return null;
  }
  const missing = BigInt(value.knownCalls) + BigInt(value.notApplicableCalls) < BigInt(totals.calls);
  return <div><dt>{label}</dt><dd>
    {tokenCount(value.value, t.formatLocale) ?? t("未返回")}
    {missing && value.value !== null && <small>{t("{0} 次调用已返回", [tokenCount(value.knownCalls, t.formatLocale) ?? "0"])}</small>}
  </dd></div>;
}
