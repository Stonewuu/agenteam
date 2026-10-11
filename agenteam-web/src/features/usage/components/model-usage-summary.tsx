"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {tokenCount, usageAmount} from "../lib/model-usage-display";
import type {ModelUsageTotals} from "../types/model-usage";
import styles from "./model-usage.module.css";

export function ModelUsageSummaryCards({totals}: {totals: ModelUsageTotals}) {
  const t = useT();
  return <div className={styles.stack}>
    <div className={styles.tokens}>{[["input", t("全部输入")], ["output", t("输出")], ["cache_read", t("缓存读取")], ["cache_write", t("缓存创建")]].map(([key, label]) => {
      const value = totals.tokens[key];
      return <div key={key} className={styles.metric}><span>{label}</span><strong>{tokenCount(value?.value, t.formatLocale)
        ?? (value?.notApplicableCalls === totals.calls && totals.calls !== "0" ? t("不适用") : t("未返回"))}</strong>
        {value && value.knownCalls !== totals.calls && value.notApplicableCalls !== totals.calls
          && <small>{t("{0} 次调用已返回", [tokenCount(value.knownCalls, t.formatLocale) ?? "0"])}</small>}</div>;
    })}</div>
    <p className={styles.hint}>{t("共 {0} 次模型调用。缓存读取与创建已包含在输入中，思考已包含在输出中。", [tokenCount(totals.calls, t.formatLocale) ?? "0"])}</p>
    {totals.amounts.length > 0 && <div className={styles.money}>{totals.amounts.map((value) => <section key={value.currency}>
      <strong>{value.currency}</strong>
      {value.completeAmount !== null && <p title={value.completeAmount}>{t("完整计价")} <b>{usageAmount(value.completeAmount, t.formatLocale)}</b></p>}
      {value.partialAmount !== null && <p title={value.partialAmount}>{t("已确认部分")} <b>{usageAmount(value.partialAmount, t.formatLocale)}</b></p>}
      {value.completeAmount === null && value.partialAmount === null && <p>{t("暂无已确认金额")}</p>}
    </section>)}</div>}
    {(totals.incompleteUsageCalls !== "0" || totals.unpricedCalls !== "0" || totals.pendingCalls !== "0") && <div className={styles.notices}>
      {totals.incompleteUsageCalls !== "0" && <p>{t("有 {0} 次调用未返回完整用量。", [tokenCount(totals.incompleteUsageCalls, t.formatLocale) ?? "0"])}</p>}
      {totals.unpricedCalls !== "0" && <p>{t("有 {0} 次调用未完整计价。", [tokenCount(totals.unpricedCalls, t.formatLocale) ?? "0"])}</p>}
      {totals.pendingCalls !== "0" && <p>{t("有 {0} 次调用尚未完成金额计算。", [tokenCount(totals.pendingCalls, t.formatLocale) ?? "0"])}</p>}
    </div>}
  </div>;
}
