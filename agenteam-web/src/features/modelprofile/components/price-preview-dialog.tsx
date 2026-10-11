"use client";

import {useState} from "react";
import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {useT} from "@/lib/i18n/locale-provider";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {priceMeters, type PricePreview, type PriceVersion} from "../types/model-pricing";
import {priceReasons} from "@/features/usage/lib/model-usage-display";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

const labels = {inputTokens: "全部输入", outputTokens: "输出", cacheReadTokens: "缓存读取", cacheWriteTokens: "缓存创建"};

export function PricePreviewDialog({enterpriseId, modelId, version, onClose}: {
  enterpriseId: string; modelId: string; version: PriceVersion; onClose: () => void;
}) {
  const t = useT();
  const action = useFormAction();
  const [at, setAt] = useState(() => {
    const now = new Date();
    return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  });
  const [tier, setTier] = useState("standard");
  const [counts, setCounts] = useState<Record<keyof typeof labels, string>>({inputTokens: "", outputTokens: "", cacheReadTokens: "", cacheWriteTokens: ""});
  const [details, setDetails] = useState<Record<string, string>>({});
  const [result, setResult] = useState<PricePreview | null>(null);
  const durations = Array.from(new Set(version.configuration?.rules.flatMap((rule) => rule.rates.filter((rate) => rate.cacheTtlSeconds > 0).map((rate) => rate.cacheTtlSeconds)) ?? []));
  return <Dialog title={t("计算预览 · 版本 {0}", [version.versionNo])} onClose={onClose} busy={action.busy} wide
    footer={<DialogCancel className={ui.button} disabled={action.busy}>{t("关闭")}</DialogCancel>}>
    <AnimatedHeight preserveControlShadows><div className={styles.stack}>
      <form className={styles.stack} onSubmit={(event) => {
        event.preventDefault();
        void action.execute(async () => {
          setResult(await action.mutation.run<PricePreview>(organizationPath(enterpriseId, `/models/${encodeURIComponent(modelId)}/price-versions/${encodeURIComponent(version.id)}/preview`), {
            method: "POST", body: {dispatchedAt: new Date(at).toISOString(), actualServiceTier: tier || null,
              requestedServiceTier: tier === "" ? "auto" : null,
              ...Object.fromEntries(Object.entries(counts).map(([key, value]) => [key, value === "" ? null : Number(value)])),
              cacheWriteDetails: Object.fromEntries(Object.entries(details).filter(([, value]) => value !== "").map(([key, value]) => [key, Number(value)]))},
          }));
        }, "");
      }}>
        <div className={styles.columns}><Field label={t("模拟时间 · {0}", [Intl.DateTimeFormat().resolvedOptions().timeZone])}>
          <Input className={ui.input} type="datetime-local" required value={at} onChange={(event) => setAt(event.target.value)}/></Field>
          <Field label={t("实际服务档位")}><Select className={ui.select} value={tier} onChange={(event) => setTier(event.target.value)}>
            <option value="">{t("未确认（自动选择）")}</option><option value="standard">{t("标准服务")}</option>
            {['priority', 'flex', 'scale', 'batch', 'fast'].map((value) => <option key={value}>{value}</option>)}
          </Select></Field>
          {(Object.keys(labels) as (keyof typeof labels)[]).map((key) => <Field key={key} label={t(labels[key])}>
            <Input className={ui.input} type="number" min={0} max={100000000000} step={1} placeholder={t("未返回")}
              value={counts[key]} onChange={(event) => setCounts({...counts, [key]: event.target.value})}/></Field>)}
          {durations.map((ttl) => <Field key={ttl} label={t("创建缓存 · {0} 秒", [ttl])}>
            <Input className={ui.input} type="number" min={0} max={100000000000} step={1} value={details[ttl] ?? ""}
              onChange={(event) => setDetails({...details, [ttl]: event.target.value})}/></Field>)}
        </div>
        <p className={styles.hint}>{t("填写模拟用量，不会调用模型。输入总量应包含缓存读取和缓存创建。")}</p>
        <MutationFeedback action={action}/>
        <Button className={ui.primary} type="submit" disabled={action.busy}>{action.busy ? t("正在计算…") : t("计算")}</Button>
      </form>
      {result && <section className={styles.rule} aria-live="polite"><strong>{result.ruleName ?? t("没有匹配的价格规则")}</strong>
        <p>{result.totalAmount !== null ? t("完整金额：{0} {1}", [result.totalAmount, result.currency ?? ""]) :
          result.knownAmount !== null ? t("已确认部分：{0} {1}", [result.knownAmount, result.currency ?? ""]) : t("暂时无法计算金额")}</p>
        {result.reason && priceReasons[result.reason] && <p className={styles.hint}>{t(priceReasons[result.reason])}</p>}
        {result.items.length > 0 && <table className={styles.table}><thead><tr><th>{t("计量项")}</th><th>{t("数量")}</th><th>{t("金额")}</th></tr></thead><tbody>
          {result.items.map((item) => <tr key={`${item.meter}-${item.cacheTtlSeconds}`}><td>{t(priceMeters.find((rate) => rate.value === item.meter)?.label ?? item.meter)}
            {item.cacheTtlSeconds > 0 && ` · ${t("{0} 秒", [item.cacheTtlSeconds])}`}</td><td>{item.quantity}</td><td className={styles.amount}>{item.amount}</td></tr>)}
        </tbody></table>}
      </section>}
    </div></AnimatedHeight>
  </Dialog>;
}
