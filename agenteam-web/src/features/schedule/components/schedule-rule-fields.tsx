"use client";

import {Field} from "@/components/ui/field";
import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {Checkbox} from "@/components/ui/checkbox";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {TimezoneSelect} from "@/features/auth/components/timezone-select";
import {useT} from "@/lib/i18n/locale-provider";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";
import {weekdayNames} from "../lib/schedule-display";
import type {ScheduleForm} from "../lib/schedule-form";
import type {ScheduleRule} from "../types/schedule";
import ui from "@/components/ui/surface.module.css";
import styles from "./schedule.module.css";

export function ScheduleRuleFields({form, change, error}: {form: ScheduleForm; change: (patch: Partial<ScheduleForm>) => void; error: (field: string) => string}) {
  const t = useT();
  function frequency(value: ScheduleRule["frequency"]) {
    change({frequency: value, localDate: value === "once" ? form.localDate ?? "" : null,
      weekdays: value === "weekly" ? form.weekdays.length ? form.weekdays : [1] : [], monthDay: value === "monthly" ? form.monthDay ?? 1 : null});
  }
  return <>
    <div className={ui.columns}>
      <Field label={t("执行频率")} error={error("frequency")}><Select name="frequency" value={form.frequency}
        onChange={event => frequency(event.target.value as ScheduleRule["frequency"])}>
        <option value="once">{t("仅一次")}</option><option value="daily">{t("每天")}</option>
        <option value="weekly">{t("每周")}</option><option value="monthly">{t("每月")}</option>
      </Select></Field>
      <Field label={t("执行时间")} required error={error("localTime")}><Input name="localTime" type="time" step={60}
        required value={form.localTime} onChange={event => change({localTime: event.target.value})}/></Field>
    </div>
    <AnimatedHeight preserveControlShadows>
      {form.frequency === "once" && <Field label={t("执行日期")} required error={error("localDate")}><Input name="localDate" type="date"
        required min="0001-01-01" max="9999-12-31" value={form.localDate ?? ""} onChange={event => change({localDate: event.target.value})}/></Field>}
      {form.frequency === "weekly" && <Fieldset><legend>{t("执行日")}</legend><div className={styles.days}>
        {weekdayNames.map((name, index) => <label key={name}><Checkbox name="weekdays" checked={form.weekdays.includes(index + 1)}
          onCheckedChange={checked => change({weekdays: checked ? [...form.weekdays, index + 1].sort((a, b) => a - b) : form.weekdays.filter(day => day !== index + 1)})}/>{t(name)}</label>)}
      </div><FieldErrorFeedback messages={error("weekdays") ? [error("weekdays")] : []}/></Fieldset>}
      {form.frequency === "monthly" && <Field label={t("每月执行日")} required error={error("monthDay")} hint={t("当月没有这一天时跳过该月。") }>
        <Input name="monthDay" type="number" min={1} max={31} required value={form.monthDay ?? ""}
          onChange={event => change({monthDay: event.target.value ? Number(event.target.value) : null})}/>
      </Field>}
    </AnimatedHeight>
    <Field label={t("时区")} required error={error("timezone")}><TimezoneSelect value={form.timezone} required
      onChange={timezone => change({timezone})}/></Field>
  </>;
}
