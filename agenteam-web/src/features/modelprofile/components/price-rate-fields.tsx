"use client";

import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {IconPlus, IconTrash} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {priceMeters, type PriceRate} from "../types/model-pricing";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-pricing.module.css";

export function PriceRateFields({rates, onChange, disabled = false}: {
  rates: PriceRate[]; onChange: (rates: PriceRate[]) => void; disabled?: boolean;
}) {
  const t = useT();
  function price(meter: PriceRate["meter"], ttl: number, value: string) {
    const index = rates.findIndex((rate) => rate.meter === meter && rate.cacheTtlSeconds === ttl);
    const next = rates.filter((_, position) => position !== index);
    if (value !== "") {
      next.push({meter, cacheTtlSeconds: ttl, pricePerMillion: value});
    }
    onChange(next);
  }
  return <div className={styles.stack}>
    <div className={styles.columns}>{priceMeters.map((meter) => <Field key={meter.value} label={t(meter.label)}>
      <Input className={ui.input} inputMode="decimal" aria-label={t("{0}单价", [t(meter.label)])} placeholder={t("未配置")}
        value={rates.find((rate) => rate.meter === meter.value && rate.cacheTtlSeconds === 0)?.pricePerMillion ?? ""}
        maxLength={24} disabled={disabled} onChange={(event) => price(meter.value, 0, event.target.value)}/>
    </Field>)}</div>
    {rates.filter((rate) => rate.cacheTtlSeconds > 0).map((rate) => <div className={styles.rateRow} key={`${rate.meter}-${rate.cacheTtlSeconds}`}>
      <Field label={t("缓存有效期")}><Select className={ui.select} disabled={disabled} value={rate.cacheTtlSeconds}
        onChange={(event) => onChange(rates.map((item) => item === rate ? {...item, cacheTtlSeconds: Number(event.target.value)} : item))}>
        <option value={300}>{t("5 分钟")}</option><option value={3600}>{t("1 小时")}</option>
        {![300, 3600].includes(rate.cacheTtlSeconds) && <option value={rate.cacheTtlSeconds}>{t("{0} 秒", [rate.cacheTtlSeconds])}</option>}
      </Select></Field>
      <Field label={t("缓存创建单价")}><Input className={ui.input} inputMode="decimal" disabled={disabled} value={rate.pricePerMillion}
        onChange={(event) => onChange(rates.map((item) => item === rate ? {...item, pricePerMillion: event.target.value} : item))}/></Field>
      <Button className="icon-button" type="button" aria-label={t("移除此单价")} disabled={disabled}
        onClick={() => onChange(rates.filter((item) => item !== rate))}><IconTrash size={17}/></Button>
    </div>)}
    <Button type="button" className={ui.button} disabled={disabled || [300, 3600].every((ttl) => rates.some((rate) => rate.cacheTtlSeconds === ttl))}
      onClick={() => onChange([...rates, {meter: "cache_write", cacheTtlSeconds: rates.some((rate) => rate.cacheTtlSeconds === 300) ? 3600 : 300, pricePerMillion: ""}])}>
      <IconPlus size={16}/>{t("按缓存有效期添加单价")}
    </Button>
  </div>;
}
