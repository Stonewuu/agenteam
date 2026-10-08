type DatedConversation = { updatedAt: string };
type GroupKey = "today" | "yesterday" | "earlier" | "other";
type ConversationDateGroup<T> = { key: GroupKey; label: string; items: T[] };

function calendarDay(value: Date, formatter: Intl.DateTimeFormat) {
  if (!Number.isFinite(value.getTime())) {
    return null;
  }
  const parts = formatter.formatToParts(value);
  const year = Number(parts.find((part) => part.type === "year")?.value);
  const month = Number(parts.find((part) => part.type === "month")?.value);
  const day = Number(parts.find((part) => part.type === "day")?.value);
  // 按企业当地的日历日期比较，避免夏令时切换时一天不足或超过 24 小时。
  return Date.UTC(year, month - 1, day) / 86_400_000;
}

export function groupConversationsByDate<T extends DatedConversation>(items: readonly T[], timezone: string, now: Date) {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    calendar: "gregory",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
  const today = calendarDay(now, formatter);
  const groups: ConversationDateGroup<T>[] = [
    {key: "today", label: "今天", items: []},
    {key: "yesterday", label: "昨天", items: []},
    {key: "earlier", label: "更早", items: []},
    {key: "other", label: "其他日期", items: []},
  ];
  for (const item of items) {
    const day = calendarDay(new Date(item.updatedAt), formatter);
    let index = 3;
    if (day !== null && today !== null) {
      if (day === today) {
        index = 0;
      } else if (day === today - 1) {
        index = 1;
      } else if (day < today - 1) {
        index = 2;
      }
    }
    groups[index].items.push(item);
  }
  return groups.filter((group) => group.items.length > 0);
}
