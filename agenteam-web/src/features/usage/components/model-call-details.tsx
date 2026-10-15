"use client";

import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {DetailSection} from "@/components/ui/detail-section";
import {IconChartBar, IconClock, IconReceipt, IconRobot, IconUser, IconWallet} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {priceReasons, tokenCount, tokenLabels, usageAmount, usagePurposes} from "../lib/model-usage-display";
import type {ModelCall} from "../types/model-usage";
import {ModelCallTokenUsage} from "./model-call-token-usage";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-usage.module.css";

export function ModelCallDetails({call, onClose}: {call: ModelCall; onClose: () => void}) {
  const t = useT();
  const status: Record<string, string> = {registered: "等待请求", in_flight: "调用中", completed: "已结束", failed: "调用失败", cancelled: "已取消", interrupted: "调用中断"};
  return <Dialog title={call.modelProfileName || call.modelName} icon={<IconRobot size={21}/>} onClose={onClose} wide
    footer={<DialogCancel className={ui.button}>{t("关闭")}</DialogCancel>}>
    <div className={styles.stack}>
      <div className={styles.toolbar}><p>{call.modelName} · {call.providerName} · {t(usagePurposes[call.purpose] ?? "模型调用")}</p>
        <span className="badge neutral">{t(status[call.callStatus] ?? "已结束")}</span></div>
      <section className={styles.moneyCard}><span className={styles.moneyIcon} data-icon-surface><IconWallet size={24} variant="Bulk"/></span>
        <div className={styles.moneyCopy}><span>{call.totalAmount !== null ? t("已计价费用") : call.knownAmount !== null ? t("已确认部分") : t("费用")}</span>
          <div className={styles.moneyValue}><strong title={call.totalAmount ?? call.knownAmount ?? undefined}>
            {call.totalAmount !== null ? usageAmount(call.totalAmount, t.formatLocale) : call.knownAmount !== null ? usageAmount(call.knownAmount, t.formatLocale)
              : call.priceStatus === "pending" ? t("金额待计算") : call.reasonCode === "PRICE_NOT_CONFIGURED" ? t("当时未配置价格") : t("未计价")}</strong>
            {(call.totalAmount !== null || call.knownAmount !== null) && <span>{call.currency}</span>}</div>
          {call.reasonCode && priceReasons[call.reasonCode] && <small>{t(priceReasons[call.reasonCode])}</small>}
        </div></section>
      <dl className={styles.callMetadata}>
        <div><dt><IconUser size={16}/>{t("调用用户")}</dt><dd>{call.userName || t("未提供姓名")}</dd></div>
        <div><dt><IconClock size={16}/>{t("发生时间")}</dt>
          <dd>{new Date(call.dispatchedAt).toLocaleString(t.formatLocale)}{call.finishedAt && <> – {new Date(call.finishedAt).toLocaleString(t.formatLocale)}</>}</dd></div>
      </dl>
      <DetailSection title={t("Token 用量")} icon={<IconChartBar size={20}/>}>
        <ModelCallTokenUsage call={call}/>
      </DetailSection>
      <DetailSection title={t("费用明细")} icon={<IconReceipt size={20}/>}>
        <div className={styles.stack}>
          {call.items.length > 0 && <div className={styles.tableScroll}><table className={styles.table}><thead><tr><th>{t("计量项")}</th><th>{t("数量")}</th><th>{t("每百万单价")}</th><th>{t("金额")}</th></tr></thead><tbody>
            {call.items.map((item) => <tr key={`${item.meter}-${item.cacheTtlSeconds}`}><td>{t(tokenLabels[item.meter] ?? item.meter)}
              {item.cacheTtlSeconds > 0 && ` · ${t("{0} 秒", [item.cacheTtlSeconds])}`}</td><td>{tokenCount(item.quantity, t.formatLocale)}</td>
              <td>{item.pricePerMillion}</td><td>{item.amount} {call.currency}</td></tr>)}
          </tbody></table></div>}
          {call.totalAmount !== null && <p className={styles.exactAmount}>{t("完整金额：{0} {1}", [call.totalAmount, call.currency ?? ""])}</p>}
          {call.items.length === 0 && <p className={styles.hint}>{t("暂无已确认金额")}</p>}
        </div>
      </DetailSection>
    </div>
  </Dialog>;
}
