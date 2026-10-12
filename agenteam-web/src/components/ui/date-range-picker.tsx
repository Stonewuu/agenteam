"use client";

import {useId, useRef, useState} from "react";
import type {DateRange} from "@daypicker/react";
import {Button} from "./button";
import {Calendar} from "./calendar";
import {DateTimePicker} from "./date-time-picker";
import {IconArrowRight, IconCalendar} from "./icons";
import {useFieldContext} from "./field";
import {useControlDisabled} from "./fieldset";
import {dateValue, parseDate} from "./date-time-value";
import {dateRangeAllowed, dateRangeDays, dateRangePreset, orderedDateRange, type DateRangeValue, type DateRangePreset} from "./date-range-value";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "./surface.module.css";
import styles from "./date-range-picker.module.css";

/** 沿用 shadcn Date Picker 范围示例，完整触发区打开日历，确认完整范围后才更新筛选。 */
export function DateRangePicker({value, onChange, maxDays, disabled}: {
  value: DateRangeValue; onChange: (value: DateRangeValue) => void; maxDays?: number; disabled?: boolean;
}) {
  const t = useT();
  const field = useFieldContext();
  const inactive = useControlDisabled(disabled);
  const trigger = useRef<HTMLButtonElement>(null);
  const valueId = useId();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<DateRange>({from: parseDate(value.from), to: parseDate(value.to)});
  const [hovered, setHovered] = useState<Date>();
  const [month, setMonth] = useState(() => parseDate(value.from) ?? new Date());
  const complete = draft.from && draft.to ? orderedDateRange(draft.from, draft.to) : undefined;
  const allowed = Boolean(complete && dateRangeAllowed(complete, maxDays));
  const previewValue = draft.from && !draft.to && hovered ? orderedDateRange(draft.from, hovered) : undefined;
  const preview = previewValue && dateRangeAllowed(previewValue, maxDays)
    ? {from: parseDate(previewValue.from), to: parseDate(previewValue.to)} : undefined;
  const shortcuts: {value: DateRangePreset; label: string}[] = [
    {value: "today", label: t("今天")}, {value: "week", label: t("近 7 天")},
    {value: "month", label: t("近 30 天")}, {value: "currentMonth", label: t("本月")},
  ];

  function openChanged(next: boolean) {
    if (next) {
      setDraft({from: parseDate(value.from), to: parseDate(value.to)});
      setMonth(parseDate(value.from) ?? new Date());
      setHovered(undefined);
    }
    setOpen(next);
  }

  function apply(next: DateRangeValue) {
    if (dateRangeAllowed(next, maxDays)) {
      onChange(next);
      setOpen(false);
    }
  }

  return <DateTimePicker open={open} onOpenChange={openChanged} title={t("选择日期范围")} finalFocus={trigger} wide
    trigger={<Button ref={trigger} type="button" disabled={inactive} className={`${ui.input} ${styles.trigger}`}
      aria-labelledby={field ? `${field.labelId} ${valueId}` : valueId} aria-describedby={field?.describedBy} aria-invalid={field?.invalid || undefined}>
      <span id={valueId} className={styles.value}>
        <span>{value.from || t("开始日期")}</span><IconArrowRight size={16}/><span>{value.to || t("结束日期")}</span>
      </span><IconCalendar size={18}/>
    </Button>}>
    {(narrow) => <>
      <div className={styles.toolbar}>
        <div className={styles.shortcuts}>{shortcuts.map((shortcut) => {
          const range = dateRangePreset(shortcut.value);
          return <Button key={shortcut.value} type="button" className={ui.button}
            aria-pressed={complete?.from === range.from && complete.to === range.to}
            disabled={!dateRangeAllowed(range, maxDays)} onClick={() => apply(range)}>{shortcut.label}</Button>;
        })}</div>
        <p className={styles.instruction} aria-live="polite">{draft.from && !draft.to ? t("请选择结束日期") : t("选择起止日期")}</p>
      </div>
      <Calendar mode="range" value={draft} onChange={(next) => {
        setDraft(next);
        setHovered(undefined);
      }} month={month} onMonthChange={(next) => {
        setMonth(next);
        setHovered(undefined);
      }} numberOfMonths={narrow ? 1 : 2} preview={preview} onPreview={setHovered}
        disabled={(day) => Boolean(draft.from && !draft.to && !dateRangeAllowed(orderedDateRange(draft.from, day), maxDays))}/>
      <div className={styles.footer}>
        <span className={styles.summary}>{complete ? <><strong>{complete.from} — {complete.to}</strong><span>{t("共 {0} 天", [dateRangeDays(complete)])}</span></>
          : draft.from ? <><strong>{dateValue(draft.from)}</strong><span>{t("开始日期")}</span></> : t("选择起止日期")}</span>
        <div className={styles.actions}><Button type="button" className={ui.button} onClick={() => setOpen(false)}>{t("取消")}</Button>
          <Button type="button" className={ui.primary} disabled={!allowed} onClick={() => {
            if (complete) {
              apply(complete);
            }
          }}>{t("应用")}</Button></div>
      </div>
    </>}
  </DateTimePicker>;
}
