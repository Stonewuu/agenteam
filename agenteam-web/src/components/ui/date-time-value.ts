export type DateTimeInputType = "date" | "time" | "datetime-local";

export function isDateTimeInput(type: string | undefined): type is DateTimeInputType {
  return type === "date" || type === "time" || type === "datetime-local";
}

const pad = (value: number) => String(value).padStart(2, "0");

/** 日期字段表示本地日历日期，不能经 UTC 字符串转换后再读取。 */
export function dateValue(value: Date) {
  return `${String(value.getFullYear()).padStart(4, "0")}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`;
}

/** 日历按钮可能表示零点，范围解析使用中午；这里只比较日期，避免禁用边界当天。 */
export function dateWithinBounds(day: Date, min?: Date, max?: Date) {
  const value = dateValue(day);
  return (!min || value >= dateValue(min)) && (!max || value <= dateValue(max));
}

export function parseDate(value: string): Date | undefined {
  const match = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(value);
  if (!match) {
    return undefined;
  }
  const [year, month, day] = match.slice(1).map(Number);
  const result = new Date(0);
  result.setFullYear(year, month - 1, day);
  result.setHours(12, 0, 0, 0);
  return year > 0 && result.getFullYear() === year && result.getMonth() === month - 1 && result.getDate() === day
    ? result : undefined;
}

export function normalizeDateTime(value: string, type: DateTimeInputType): string | null {
  const text = value.trim().replaceAll("/", "-");
  if (!text) {
    return "";
  }
  if (type === "date") {
    const date = parseDate(text);
    return date ? dateValue(date) : null;
  }
  const parts = type === "time" ? ["", text] : text.split(/[T ]/);
  if (parts.length !== 2) {
    return null;
  }
  const [day, time] = parts;
  const date = type === "datetime-local" ? parseDate(day) : undefined;
  const match = /^(\d{1,2}):(\d{2})(?::(\d{2})(\.\d{1,3})?)?$/.exec(time ?? "");
  if (!match || Number(match[1]) > 23 || Number(match[2]) > 59 || Number(match[3] ?? 0) > 59
    || type === "datetime-local" && !date) {
    return null;
  }
  const clock = `${pad(Number(match[1]))}:${match[2]}${match[3] ? `:${match[3]}${match[4] ?? ""}` : ""}`;
  return type === "time" ? clock : `${dateValue(date!)}T${clock}`;
}

export function dateTimeText(value: string) {
  return value.replace("T", " ");
}

export function currentDateTime(type: DateTimeInputType, seconds = false, now = new Date()) {
  const clock = `${pad(now.getHours())}:${pad(now.getMinutes())}${seconds ? `:${pad(now.getSeconds())}` : ""}`;
  return type === "date" ? dateValue(now) : type === "time" ? clock : `${dateValue(now)}T${clock}`;
}

export function timePart(value: string, type: DateTimeInputType) {
  return type === "datetime-local" ? value.split("T")[1] ?? "00:00" : value || "00:00";
}

function numericValue(value: string, type: DateTimeInputType): number | null {
  const normalized = normalizeDateTime(value, type);
  if (!normalized) {
    return null;
  }
  if (type === "time") {
    const [hours, minutes, seconds = 0] = normalized.split(":").map(Number);
    return (hours * 3600 + minutes * 60 + seconds) * 1000;
  }
  // 这里只计算日历字段间隔，统一按零时区计数，不改变字段表示的本地日期或时间。
  return Date.parse(type === "date" ? `${normalized}T00:00:00Z` : `${normalized}Z`);
}

export function dateTimeAllowed(value: string, {type, min, max, step, base}: {
  type: DateTimeInputType; min?: string | number; max?: string | number; step?: string | number; base?: string;
}) {
  const number = numericValue(value, type);
  if (number === null) {
    return false;
  }
  const minimum = numericValue(String(min ?? ""), type);
  const maximum = numericValue(String(max ?? ""), type);
  if (type === "time" && minimum !== null && maximum !== null && minimum > maximum) {
    if (number < minimum && number > maximum) {
      return false;
    }
  } else if (minimum !== null && number < minimum || maximum !== null && number > maximum) {
    return false;
  }
  if (step === "any") {
    return true;
  }
  const requested = Number(step);
  const interval = Number.isFinite(requested) && requested > 0 ? requested : type === "date" ? 1 : 60;
  const origin = minimum ?? numericValue(base ?? "", type) ?? 0;
  const multiple = (number - origin) / (interval * (type === "date" ? 86400000 : 1000));
  return Math.abs(multiple - Math.round(multiple)) < 1e-7;
}
