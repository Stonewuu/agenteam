import {sourceText, type TextFormatter} from "./i18n/format";

export const timezones = [
  ["Asia/Shanghai", "北京"], ["Asia/Hong_Kong", "香港"], ["Asia/Taipei", "台北"], ["Asia/Tokyo", "东京"],
  ["Asia/Singapore", "新加坡"], ["Asia/Kolkata", "新德里"], ["Asia/Dubai", "迪拜"], ["Europe/London", "伦敦"],
  ["Europe/Paris", "巴黎"], ["Europe/Berlin", "柏林"], ["America/New_York", "纽约"], ["America/Chicago", "芝加哥"],
  ["America/Los_Angeles", "洛杉矶"], ["America/Toronto", "多伦多"], ["America/Sao_Paulo", "圣保罗"],
  ["Australia/Sydney", "悉尼"], ["Pacific/Auckland", "奥克兰"], ["UTC", "协调世界时"],
];

export function timezoneLabel(value: string, t: TextFormatter = sourceText) {
  const known = timezones.find(([zone]) => zone === value);
  if (known) {
    return t(known[1]);
  }
  try {
    return new Intl.DateTimeFormat(t.formatLocale ?? "zh-CN", {
      timeZone: value,
      timeZoneName: "long"
    }).formatToParts(new Date()).find((part) => part.type === "timeZoneName")?.value ?? t("当前时区");
  } catch {
    return t("请选择有效时区");
  }
}
