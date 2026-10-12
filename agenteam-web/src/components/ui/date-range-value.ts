import {dateValue, parseDate} from "./date-time-value";

export type DateRangeValue = {from: string; to: string};
export type DateRangePreset = "today" | "week" | "month" | "currentMonth";

export function orderedDateRange(first: Date, last: Date): DateRangeValue {
  const from = dateValue(first);
  const to = dateValue(last);
  return from <= to ? {from, to} : {from: to, to: from};
}

export function dateRangeDays(value: DateRangeValue) {
  if (!parseDate(value.from) || !parseDate(value.to) || value.from > value.to) {
    return 0;
  }
  return (Date.parse(`${value.to}T00:00:00Z`) - Date.parse(`${value.from}T00:00:00Z`)) / 86400000 + 1;
}

/** 查询时间包含结束当天，与接口既有的连续小时上限保持一致。 */
export function dateRangeAllowed(value: DateRangeValue, maxDays?: number) {
  if (!dateRangeDays(value)) {
    return false;
  }
  if (maxDays === undefined) {
    return true;
  }
  const start = new Date(`${value.from}T00:00:00`);
  const end = new Date(`${value.to}T00:00:00`);
  end.setDate(end.getDate() + 1);
  return end.getTime() - start.getTime() <= maxDays * 86400000;
}

export function dateRangePreset(preset: DateRangePreset, now = new Date()): DateRangeValue {
  const from = new Date(now);
  from.setHours(12, 0, 0, 0);
  if (preset === "currentMonth") {
    from.setDate(1);
  } else {
    from.setDate(from.getDate() - (preset === "week" ? 6 : preset === "month" ? 29 : 0));
  }
  return {from: dateValue(from), to: dateValue(now)};
}
