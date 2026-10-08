import type {Occurrence, ScheduleRule} from "../types/schedule";
import {sourceText, type TextFormatter} from "../../../lib/i18n/format";

export const weekdayNames = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
export const occurrenceNames: Record<Occurrence["status"], string> = {
  queued: "等待执行",
  running: "执行中",
  waiting_approval: "等待确认",
  completed: "已完成",
  failed: "未完成",
  cancelled: "已停止",
  skipped: "已跳过",
  missed: "已错过",
  blocked: "未能开始",
  partially_failed: "部分未完成",
  unknown: "部分结果待确认"
};

export function occurrenceActive(value: Occurrence) {
  return ["queued", "running", "waiting_approval"].includes(value.status);
}

export function scheduleTime(value: string | null, timezone: string, locale = "zh-CN") {
  if (!value) {
    return "—";
  }
  return new Intl.DateTimeFormat(locale, {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(new Date(value));
}

export function scheduleRuleText(rule: ScheduleRule, t: TextFormatter = sourceText) {
  const prefix = rule.frequency === "once" ? rule.localDate : rule.frequency === "daily" ? t("每天") : rule.frequency === "weekly" ? rule.weekdays.map((day) => t(weekdayNames[day - 1])).join(t("、")) : t("每月 {0} 日", [rule.monthDay]);
  return `${prefix} ${rule.localTime.slice(0, 5)}`;
}
