"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconChevronRight, IconClock, IconRobot} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {ModelCallDetails} from "./model-call-details";
import {usageAmount, usagePurposes, tokenCount} from "../lib/model-usage-display";
import type {ModelCall} from "../types/model-usage";
import styles from "./model-usage.module.css";

export function ModelCallList({calls}: {calls: ModelCall[]}) {
  const t = useT();
  const [selected, setSelected] = useState<string | null>(null);
  const current = calls.find((value) => value.id === selected);
  return <><div className={styles.callList}>{calls.map((call) => <Button className={styles.callRow} key={call.id} onClick={() => setSelected(call.id)}>
    <span className={styles.callIcon}><IconRobot size={21} variant="Bulk"/></span>
    <span className={styles.callCopy}><strong>{call.modelName}</strong><small><IconClock size={13}/>{t(usagePurposes[call.purpose] ?? "模型调用")} · {new Date(call.dispatchedAt).toLocaleString(t.formatLocale)}</small></span>
    <span className={styles.callAmounts} data-complete={call.priceStatus === "priced"}><strong title={call.totalAmount ?? call.knownAmount ?? undefined}>
      {call.totalAmount !== null ? `${usageAmount(call.totalAmount, t.formatLocale)} ${call.currency}` : call.knownAmount !== null ?
        t("已确认部分：{0} {1}", [usageAmount(call.knownAmount, t.formatLocale), call.currency ?? ""]) : call.priceStatus === "pending" ? t("金额待计算") : call.reasonCode === "PRICE_NOT_CONFIGURED" ? t("当时未配置价格") : t("未计价")}
    </strong><small>{t("输入 {0} · 输出 {1}", [tokenCount(call.tokens.input?.value, t.formatLocale) ?? t("未返回"),
      tokenCount(call.tokens.output?.value, t.formatLocale) ?? t("未返回")])}</small></span><IconChevronRight size={17}/>
  </Button>)}</div>{current && <ModelCallDetails call={current} onClose={() => setSelected(null)}/>}</>;
}
