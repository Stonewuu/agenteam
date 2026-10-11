"use client";

import {useState} from "react";
import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {DetailSection} from "@/components/ui/detail-section";
import {IconClock, IconChartBar, IconCalendar, IconPlus, IconTrash, IconAdjustments} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {PriceRateFields} from "./price-rate-fields";
import type {PriceRate, PriceRule} from "../types/model-pricing";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

const timeValue = (minutes: number) => `${String(Math.floor((minutes % 1440) / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
const minutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5));

export function PriceRuleDialog({initial, baseRates, unit, onClose, onSave}: {
  initial: PriceRule; baseRates: PriceRate[]; unit: string; onClose: () => void; onSave: (rule: PriceRule) => void;
}) {
  const t = useT();
  const [rule, setRule] = useState<PriceRule>(() => structuredClone(initial));
  const closing = useConfirmClose(JSON.stringify(rule) !== JSON.stringify(initial), false, onClose);
  const days = [t("周一"), t("周二"), t("周三"), t("周四"), t("周五"), t("周六"), t("周日")];
  return <><Dialog title={initial.name || t("新增价格规则")} wide onClose={onClose} dialogRef={closing.dialogRef}
    onRequestClose={closing.canClose} footer={<><DialogCancel className={ui.button}>{t("取消")}</DialogCancel>
      <Button type="submit" form="model-price-rule" className={ui.primary}>{t("保留此规则")}</Button></>}>
    <AnimatedHeight preserveControlShadows><form id="model-price-rule" className={styles.stack} onSubmit={(event) => {
      event.preventDefault();
      closing.finish(() => onSave(rule));
    }}>
      <Field label={t("规则名称")} required><Input className={ui.input} value={rule.name} maxLength={96} required
        onChange={(event) => setRule({...rule, name: event.target.value})}/></Field>
      <DetailSection title={t("适用条件")} icon={<IconAdjustments size={20}/>}>
        <div className={styles.columns}>
          <Field label={t("优先级")} hint={t("数字较大的规则优先使用。") }><Input className={ui.input} type="number" min={1} max={1000} required value={rule.priority}
            onChange={(event) => setRule({...rule, priority: Number(event.target.value)})}/></Field>
          <Field label={t("服务档位")}><Select className={ui.select} value={rule.serviceTier} onChange={(event) => setRule({...rule, serviceTier: event.target.value})}>
            <option value="standard">{t("标准服务")}</option><option value="priority">{t("优先服务")}</option>
            {['flex', 'scale', 'batch', 'fast'].map((tier) => <option key={tier} value={tier}>{tier}</option>)}
          </Select></Field>
          <Field label={t("输入下界（包含）")}><Input className={ui.input} type="number" min={0} max={100000000000} required value={rule.contextMinTokens}
            onChange={(event) => setRule({...rule, contextMinTokens: Number(event.target.value)})}/></Field>
          <Field label={t("输入上界（不包含）")}><Input className={ui.input} type="number" min={rule.contextMinTokens + 1} max={100000000001}
            placeholder={t("无上限")} value={rule.contextMaxTokens ?? ""}
            onChange={(event) => setRule({...rule, contextMaxTokens: event.target.value ? Number(event.target.value) : null})}/></Field>
        </div>
      </DetailSection>
      <DetailSection title={t("单价 · {0}", [unit])} icon={<IconChartBar size={20}/>} action={<Button type="button" className={ui.button}
        onClick={() => setRule({...rule, rates: structuredClone(baseRates)})}>{t("复制基础单价")}</Button>}>
        <PriceRateFields rates={rule.rates} onChange={(rates) => setRule({...rule, rates})}/>
      </DetailSection>
      <DetailSection title={t("星期与时段")} icon={<IconClock size={20}/>} description={t("未添加时段时适用于每天全天。跨午夜的结束时间按次日计算。")}
        action={<Button className={ui.button} type="button" onClick={() => setRule({...rule, times: [...rule.times, {weekday: 1, startMinute: 540, endMinute: 1080}]})}>
          <IconPlus size={16}/>{t("添加时段")}</Button>}>
        {rule.times.map((time, index) => <div className={styles.timeRow} key={index}>
          <Select className={ui.select} aria-label={t("星期")} value={time.weekday} onChange={(event) => setRule({...rule,
            times: rule.times.map((item, i) => i === index ? {...item, weekday: Number(event.target.value)} : item)})}>
            {days.map((day, dayIndex) => <option key={day} value={dayIndex + 1}>{day}</option>)}
          </Select>
          <Input className={ui.input} type="time" aria-label={t("开始时间")} required value={timeValue(time.startMinute)} onChange={(event) => setRule({...rule,
            times: rule.times.map((item, i) => i === index ? {...item, startMinute: minutes(event.target.value)} : item)})}/>
          <Input className={ui.input} type="time" aria-label={t("结束时间")} required value={timeValue(time.endMinute)} onChange={(event) => setRule({...rule,
            times: rule.times.map((item, i) => i === index ? {...item, endMinute: minutes(event.target.value) || 1440} : item)})}/>
          <Button type="button" className="icon-button" aria-label={t("移除此时段")} onClick={() => setRule({...rule, times: rule.times.filter((_, i) => i !== index)})}><IconTrash size={17}/></Button>
        </div>)}
      </DetailSection>
      <DetailSection title={t("指定日期")} icon={<IconCalendar size={20}/>} description={t("可只在指定日期使用，或排除节假日等日期。")}
        action={<Button className={ui.button} type="button" onClick={() => setRule({...rule, dates: [...rule.dates, {localDate: "", kind: "exclude", note: null}]})}>
          <IconPlus size={16}/>{t("添加日期")}</Button>}>
        {rule.dates.map((date, index) => <div className={styles.dateRow} key={index}>
          <Input className={ui.input} type="date" required aria-label={t("指定日期")} value={date.localDate} onChange={(event) => setRule({...rule,
            dates: rule.dates.map((item, i) => i === index ? {...item, localDate: event.target.value} : item)})}/>
          <Select className={ui.select} aria-label={t("日期范围")} value={date.kind} onChange={(event) => setRule({...rule,
            dates: rule.dates.map((item, i) => i === index ? {...item, kind: event.target.value as "include" | "exclude"} : item)})}>
            <option value="exclude">{t("排除此日")}</option><option value="include">{t("仅包含此日")}</option>
          </Select>
          <Input className={ui.input} aria-label={t("日期备注")} placeholder={t("备注（选填）")} value={date.note ?? ""} maxLength={100}
            onChange={(event) => setRule({...rule, dates: rule.dates.map((item, i) => i === index ? {...item, note: event.target.value || null} : item)})}/>
          <Button type="button" className="icon-button" aria-label={t("移除此日期")} onClick={() => setRule({...rule, dates: rule.dates.filter((_, i) => i !== index)})}><IconTrash size={17}/></Button>
        </div>)}
      </DetailSection>
    </form></AnimatedHeight>
  </Dialog>{closing.confirmation}</>;
}
