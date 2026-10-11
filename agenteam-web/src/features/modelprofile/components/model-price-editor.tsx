"use client";

import {useId, useState} from "react";
import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {Textarea} from "@/components/ui/textarea";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {DetailSection} from "@/components/ui/detail-section";
import {IconChartBar, IconAdjustments, IconPencil, IconPlus, IconTrash, IconBook2} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {PriceRuleDialog} from "./price-rule-dialog";
import {PriceRateFields} from "./price-rate-fields";
import {initialPriceRule, shiftPrice, type PriceConfiguration, type PriceRule, type PriceVersion} from "../types/model-pricing";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

export function ModelPriceEditor({enterpriseId, modelId, version, onClose, onSaved}: {
  enterpriseId: string; modelId: string; version: PriceVersion | null; onClose: () => void; onSaved: (value: PriceVersion) => void;
}) {
  const t = useT();
  const action = useFormAction();
  const id = useId();
  const [draft, setDraft] = useState<PriceConfiguration>(() => structuredClone(version?.configuration ?? {
    currency: "CNY", timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC", calendarValidThrough: null,
    sourceReference: null, sourceCheckedAt: null, note: null, rules: [initialPriceRule(true)],
  }));
  const [initial] = useState(JSON.stringify(draft));
  const [unit, setUnit] = useState("1000000");
  const [editing, setEditing] = useState<{index: number; rule: PriceRule} | null>(null);
  const closing = useConfirmClose(JSON.stringify(draft) !== initial, action.busy, onClose);
  const baseIndex = draft.rules.findIndex((rule) => rule.defaultRule);
  const base = draft.rules[baseIndex];
  const currencies = Array.from(new Set([draft.currency, "CNY", "USD", "EUR", "HKD", "JPY", "GBP", ...Intl.supportedValuesOf("currency")]));
  const zones = Array.from(new Set([draft.timezone, "UTC", "Asia/Shanghai", ...Intl.supportedValuesOf("timeZone")]));
  const unitLabel = unit === "1000" ? t("每千 Token") : t("每百万 Token");

  function changeUnit(next: string) {
    if (next === unit) {
      return;
    }
    const places = next === "1000" ? -3 : 3;
    setDraft({...draft, rules: draft.rules.map((rule) => ({...rule, rates: rule.rates.map((rate) => ({...rate,
      pricePerMillion: shiftPrice(rate.pricePerMillion, places)}))}))});
    setUnit(next);
  }

  function save() {
    const configuration = {...draft, rules: draft.rules.map((rule) => ({...rule, rates: rule.rates.map((rate) => ({...rate,
      pricePerMillion: shiftPrice(rate.pricePerMillion, unit === "1000" ? 3 : 0)}))}))};
    void action.execute(async () => {
      const path = organizationPath(enterpriseId, `/models/${encodeURIComponent(modelId)}/price-versions${version ? `/${encodeURIComponent(version.id)}` : ""}`);
      const saved = await action.mutation.run<PriceVersion>(path, {method: version ? "PUT" : "POST", revision: version?.revision,
        body: version ? configuration : {configuration}});
      closing.finish(() => onSaved(saved));
    }, "");
  }

  return <><Dialog title={version ? t("编辑价格草稿 · 版本 {0}", [version.versionNo]) : t("创建价格草稿")}
    wide onClose={onClose} busy={action.busy} dialogRef={closing.dialogRef} onRequestClose={closing.canClose}
    footer={<><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel><Button type="submit" form={id}
      className={ui.primary} disabled={action.busy}>{action.busy ? t("正在保存…") : t("保存草稿")}</Button></>}>
    <AnimatedHeight preserveControlShadows><form id={id} className={styles.stack} onSubmit={(event) => {
      event.preventDefault();
      save();
    }} onInput={action.resetFeedback}>
      <div className={styles.columns}>
        <Field label={t("币种")} error={action.fieldErrors.currency?.[0]}><Select className={ui.select} value={draft.currency} disabled={action.busy}
          onChange={(event) => setDraft({...draft, currency: event.target.value})}>{currencies.map((currency) => <option key={currency}>{currency}</option>)}</Select></Field>
        <Field label={t("计价单位")}><Select className={ui.select} value={unit} disabled={action.busy} onChange={(event) => changeUnit(event.target.value)}>
          <option value="1000000">{t("每百万 Token")}</option><option value="1000">{t("每千 Token")}</option>
        </Select></Field>
        <Field label={t("价格时区")} error={action.fieldErrors.timezone?.[0]}><Select className={ui.select} value={draft.timezone} disabled={action.busy}
          onChange={(event) => setDraft({...draft, timezone: event.target.value})}>{zones.map((zone) => <option key={zone}>{zone}</option>)}</Select></Field>
        <Field label={t("日历覆盖截止日期")} required={draft.rules.some((rule) => rule.dates.length > 0)}
          error={action.fieldErrors.calendarValidThrough?.[0]}><Input className={ui.input} type="date" value={draft.calendarValidThrough ?? ""}
          required={draft.rules.some((rule) => rule.dates.length > 0)} disabled={action.busy} onChange={(event) => setDraft({...draft, calendarValidThrough: event.target.value || null})}/></Field>
      </div>
      {base && <DetailSection title={t("基础价格")} icon={<IconChartBar size={20}/>} description={`${draft.currency} · ${unitLabel}`}>
        <PriceRateFields rates={base.rates} disabled={action.busy} onChange={(rates) => setDraft({...draft,
          rules: draft.rules.map((rule, index) => index === baseIndex ? {...rule, rates} : rule)})}/>
        <p className={styles.hint}>{t("留空表示未配置，免费项目请明确填写 0。缓存读取与创建属于输入用量。")}</p>
      </DetailSection>}
      <DetailSection title={t("更多价格规则")} icon={<IconAdjustments size={20}/>} description={t("按上下文长度、星期时段、日期或服务档位配置价格。")}
        action={<Button type="button" className={ui.button} disabled={action.busy || draft.rules.length >= 100}
          onClick={() => setEditing({index: draft.rules.length, rule: {...initialPriceRule(), rates: structuredClone(base?.rates ?? [])}})}><IconPlus size={16}/>{t("添加规则")}</Button>}>
        <div className={styles.stack}>{draft.rules.map((rule, index) => !rule.defaultRule && <article className={styles.rule} key={index}>
          <div className={styles.ruleHeading}><div className={styles.ruleCopy}><strong>{rule.name}</strong>
            <p>{t("优先级 {0} · {1} 项单价", [rule.priority, rule.rates.length])}</p></div>
            <div className={styles.actions}><Button type="button" className="icon-button" aria-label={t("编辑规则{0}", [rule.name])}
              onClick={() => setEditing({index, rule})} disabled={action.busy}><IconPencil size={17}/></Button>
              <Button type="button" className="icon-button" aria-label={t("删除规则{0}", [rule.name])} disabled={action.busy}
                onClick={() => setDraft({...draft, rules: draft.rules.filter((_, i) => i !== index)})}><IconTrash size={17}/></Button></div>
          </div>
        </article>)}</div>
      </DetailSection>
      <DetailSection title={t("价格来源与备注")} icon={<IconBook2 size={20}/>}>
        <div className={styles.stack}><Field label={t("价格来源网址")} error={action.fieldErrors.sourceReference?.[0]}>
          <Input className={ui.input} type="url" value={draft.sourceReference ?? ""} maxLength={2048} disabled={action.busy}
            onChange={(event) => setDraft({...draft, sourceReference: event.target.value || null})}/></Field>
          <Field label={t("来源核对时间（选填）")}><Input className={ui.input} type="datetime-local" disabled={action.busy}
            value={draft.sourceCheckedAt ? new Date(new Date(draft.sourceCheckedAt).getTime() - new Date(draft.sourceCheckedAt).getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ""}
            onChange={(event) => setDraft({...draft, sourceCheckedAt: event.target.value ? new Date(event.target.value).toISOString() : null})}/></Field>
          <Field label={t("备注")} error={action.fieldErrors.note?.[0]}><Textarea className={ui.textarea} value={draft.note ?? ""} maxLength={500}
            disabled={action.busy} onChange={(event) => setDraft({...draft, note: event.target.value || null})}/></Field>
        </div>
      </DetailSection>
      <MutationFeedback action={action}/>
    </form></AnimatedHeight>
  </Dialog>{closing.confirmation}
    {editing && <PriceRuleDialog initial={editing.rule} baseRates={base?.rates ?? []} unit={`${draft.currency} · ${unitLabel}`}
      onClose={() => setEditing(null)} onSave={(rule) => {
        setDraft((current) => ({...current, rules: editing.index === current.rules.length ? [...current.rules, rule]
          : current.rules.map((item, index) => index === editing.index ? rule : item)}));
        setEditing(null);
        action.resetFeedback();
      }}/>}</>;
}
