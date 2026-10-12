"use client";

import type {DateRange, DropdownProps} from "@daypicker/react";
import {zhCN} from "@daypicker/react/locale/zh-CN";
import {enUS} from "@daypicker/react/locale/en-US";
import {Select} from "./select";
import {Calendar as ShadcnCalendar} from "./shadcn/calendar";
import {dateWithinBounds, parseDate} from "./date-time-value";
import {useT} from "@/lib/i18n/locale-provider";
import styles from "./date-time-input.module.css";

function CalendarDropdown({options, ...props}: DropdownProps) {
  return <Select {...props} className={styles.calendarSelect}>
    {options?.map((option) => <option key={option.value} value={option.value} disabled={option.disabled}>{option.label}</option>)}
  </Select>;
}

/** 保留日历的方向键、跨月焦点和辅助阅读行为，只替换平台外观与年月选择。 */
type CalendarProps = {
  month: Date; onMonthChange: (value: Date) => void; min?: Date; max?: Date; disabled?: (date: Date) => boolean;
  numberOfMonths?: number; preview?: DateRange; onPreview?: (value?: Date) => void;
} & ({mode?: "single"; value?: Date; onChange: (value: Date) => void}
  | {mode: "range"; value?: DateRange; onChange: (value: DateRange) => void});

export function Calendar(props: CalendarProps) {
  const {month, onMonthChange, min, max, disabled, numberOfMonths = 1, preview, onPreview} = props;
  const t = useT();
  const currentYear = month.getFullYear();
  const start = parseDate(`${String(Math.max(1, currentYear - 100)).padStart(4, "0")}-01-01`)!;
  const end = parseDate(`${String(Math.min(9999, currentYear + 100)).padStart(4, "0")}-12-31`)!;
  return <ShadcnCalendar {...(props.mode === "range"
    ? {mode: "range" as const, required: true, resetOnSelect: true, selected: props.value, onSelect: props.onChange}
    : {mode: "single" as const, required: true, selected: props.value, onSelect: props.onChange})}
    month={month} onMonthChange={onMonthChange} numberOfMonths={numberOfMonths}
    startMonth={min && min > start ? min : start} endMonth={max && max < end ? max : end} disabled={disabled}
    captionLayout={props.mode === "range" ? "label" : "dropdown"} navLayout="around"
    fixedWeeks showOutsideDays={numberOfMonths === 1} locale={t.locale === "zh-CN" ? zhCN : enUS}
    onDayMouseEnter={(day) => onPreview?.(day)} onDayMouseLeave={() => onPreview?.(undefined)} onDayFocus={(day) => onPreview?.(day)}
    modifiers={{
      preview: (day) => Boolean(preview?.from && preview.to && dateWithinBounds(day, preview.from, preview.to)),
      previewEndpoint: preview?.from && preview.to ? [preview.from, preview.to] : [],
    }}
    modifiersClassNames={{preview: styles.rangePreview, previewEndpoint: styles.rangePreviewEndpoint}}
    components={{Dropdown: CalendarDropdown}}
    labels={{
      labelNext: () => t("下个月"), labelPrevious: () => t("上个月"),
      labelMonthDropdown: () => t("月份"), labelYearDropdown: () => t("年份"),
      labelDayButton: (day, modifiers) => `${day.toLocaleDateString(t.formatLocale, {year: "numeric", month: "long", day: "numeric", weekday: "long"})}${modifiers.today ? `，${t("今天")}` : ""}${modifiers.selected ? `，${t("已选择")}` : ""}`,
    }}
    classNames={{
      root: styles.calendar, months: `${styles.months} ${numberOfMonths > 1 ? styles.multiMonth : ""}`, month: styles.month, month_caption: styles.caption,
      dropdowns: styles.dropdowns, caption_label: styles.captionLabel, month_grid: styles.grid, weekday: styles.weekday, week: styles.week,
      day: styles.day, day_button: styles.dayButton, selected: styles.selected, today: styles.today,
      outside: styles.outside, disabled: styles.disabledDay, hidden: styles.hiddenDay,
      button_previous: styles.previous, button_next: styles.next,
      range_start: styles.rangeStart, range_end: styles.rangeEnd, range_middle: styles.rangeMiddle,
    }}/>
}
