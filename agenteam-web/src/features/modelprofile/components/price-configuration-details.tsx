"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {IconClock, IconExternalLink, IconReceipt, IconWallet} from "@/components/ui/icons";
import {priceMeters, shiftPrice, type PriceConfiguration} from "../types/model-pricing";
import styles from "./model-pricing.module.css";

function minute(value: number) {
  return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
}

export function PriceConfigurationDetails({value}: {value: PriceConfiguration}) {
  const t = useT();
  const days = [t("周一"), t("周二"), t("周三"), t("周四"), t("周五"), t("周六"), t("周日")];
  return <div className={styles.stack}>
    <p className={styles.priceContext}><IconWallet size={18}/>{t("{0} · 每百万 Token · {1}", [value.currency, value.timezone])}
      {value.calendarValidThrough && <><br/>{t("日历覆盖至 {0}", [value.calendarValidThrough])}</>}</p>
    {value.rules.map((rule, index) => <section className={styles.rule} key={index}>
      <div className={styles.ruleHeading}><strong className={styles.titleWithIcon}><IconReceipt size={18}/>{rule.name}</strong><span className="badge neutral">
        {rule.defaultRule ? t("基础价格") : t("优先级 {0}", [rule.priority])}</span></div>
      {!rule.defaultRule && <p className={styles.hint}>{t("输入从 {0} 起，上界 {1}（不包含）", [rule.contextMinTokens.toLocaleString(t.formatLocale),
        rule.contextMaxTokens === null ? t("无上限") : rule.contextMaxTokens.toLocaleString(t.formatLocale)])}<br/>
        {t("服务档位：{0}", [rule.serviceTier === "standard" ? t("标准服务") : rule.serviceTier])}</p>}
      <table className={styles.table}><thead><tr><th>{t("计量项")}</th><th>{t("单价")}</th></tr></thead><tbody>
        {rule.rates.map((rate) => <tr key={`${rate.meter}-${rate.cacheTtlSeconds}`}><td>{t(priceMeters.find((item) => item.value === rate.meter)?.label ?? rate.meter)}
          {rate.cacheTtlSeconds > 0 && ` · ${t("{0} 秒", [rate.cacheTtlSeconds])}`}</td><td className={styles.amount}>{shiftPrice(rate.pricePerMillion, 0)} {value.currency}</td></tr>)}
      </tbody></table>
      {rule.times.length > 0 && <div className={styles.hint}>{rule.times.map((time, i) => <p className={styles.titleWithIcon} key={i}><IconClock size={15}/>{days[time.weekday - 1]} {minute(time.startMinute)}–{minute(time.endMinute)}</p>)}</div>}
      {rule.dates.length > 0 && <div className={styles.hint}>{rule.dates.map((date) => <p key={date.localDate}>{date.kind === "include" ? t("包含") : t("排除")} {date.localDate}{date.note ? ` · ${date.note}` : ""}</p>)}</div>}
    </section>)}
    {value.sourceReference && <p><a className={styles.sourceLink} href={value.sourceReference} target="_blank" rel="noopener noreferrer"><IconExternalLink size={16}/>{t("查看价格来源")}</a></p>}
    {value.note && <p className={styles.hint}>{value.note}</p>}
  </div>;
}
