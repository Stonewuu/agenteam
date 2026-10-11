"use client";

import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {DetailSection} from "@/components/ui/detail-section";
import {IconChartBar, IconClock, IconListDetails} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {priceReasons, tokenCount, tokenLabels, usagePurposes} from "../lib/model-usage-display";
import type {ModelCall} from "../types/model-usage";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-usage.module.css";

export function ModelCallDetails({call, onClose}: {call: ModelCall; onClose: () => void}) {
  const t = useT();
  const status: Record<string, string> = {registered: "等待请求", in_flight: "调用中", completed: "已结束", failed: "调用失败", cancelled: "已取消", interrupted: "调用中断"};
  return <Dialog title={call.modelName} icon={<IconChartBar size={21}/>} onClose={onClose} wide
    footer={<DialogCancel className={ui.button}>{t("关闭")}</DialogCancel>}>
    <div className={styles.stack}>
      <div className={styles.toolbar}><p>{call.providerName} · {t(usagePurposes[call.purpose] ?? "模型调用")}</p>
        <span className="badge neutral">{t(status[call.callStatus] ?? "已结束")}</span></div>
      <DetailSection title={t("发生时间")} icon={<IconClock size={20}/>}>
        <p>{new Date(call.dispatchedAt).toLocaleString(t.formatLocale)}{call.finishedAt && <> – {new Date(call.finishedAt).toLocaleString(t.formatLocale)}</>}</p>
      </DetailSection>
      <DetailSection title={t("Token 用量")} icon={<IconChartBar size={20}/>} description={t("缓存属于输入子项，思考属于输出子项。") }>
        <dl className={styles.details}>{Object.entries(tokenLabels).map(([key, label]) => <div key={key}><dt>{t(label)}</dt>
          <dd>{tokenCount(call.tokens[key]?.value, t.formatLocale) ?? (call.tokens[key]?.state === "not_applicable" ? t("不适用") : t("未返回"))}</dd></div>)}
          {Object.entries(call.cacheWriteDetails).map(([ttl, count]) => <div key={ttl}><dt>{t("创建缓存 · {0} 秒", [ttl])}</dt><dd>{tokenCount(count, t.formatLocale)}</dd></div>)}
        </dl>
      </DetailSection>
      <DetailSection title={t("费用明细")} icon={<IconListDetails size={20}/>}>
        <div className={styles.stack}><p className={styles.exactAmount}>{call.totalAmount !== null ? t("完整金额：{0} {1}", [call.totalAmount, call.currency ?? ""]) :
          call.knownAmount !== null ? t("已确认部分：{0} {1}", [call.knownAmount, call.currency ?? ""]) : call.priceStatus === "pending" ? t("金额待计算") : t("未计价")}</p>
          {call.reasonCode && priceReasons[call.reasonCode] && <p className={styles.hint}>{t(priceReasons[call.reasonCode])}</p>}
          {call.items.length > 0 && <div className={styles.tableScroll}><table className={styles.table}><thead><tr><th>{t("计量项")}</th><th>{t("数量")}</th><th>{t("每百万单价")}</th><th>{t("金额")}</th></tr></thead><tbody>
            {call.items.map((item) => <tr key={`${item.meter}-${item.cacheTtlSeconds}`}><td>{t(tokenLabels[item.meter] ?? item.meter)}
              {item.cacheTtlSeconds > 0 && ` · ${t("{0} 秒", [item.cacheTtlSeconds])}`}</td><td>{tokenCount(item.quantity, t.formatLocale)}</td>
              <td>{item.pricePerMillion}</td><td>{item.amount} {call.currency}</td></tr>)}
          </tbody></table></div>}
        </div>
      </DetailSection>
    </div>
  </Dialog>;
}
