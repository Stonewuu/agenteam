import type {ActivityDay} from "../types/activity";

export function activityTotal(day: ActivityDay) {
  return day.conversations + day.schedules + day.todos + day.employees;
}

export function activityLevel(count: number, maximum: number) {
  if (count <= 0) {
    return 0;
  }
  return Math.min(4, Math.max(1, Math.ceil((count / Math.max(1, maximum)) * 4)));
}

/** 日期已经由服务端按企业时区划分，这里仅计算日历位置，避免浏览器时区改变日期。 */
export function calendarDate(value: string) {
  return new Date(`${value}T00:00:00Z`);
}

export function activityCalendar(days: ActivityDay[], locale = "zh-CN") {
  const firstWeekday = days.length ? (calendarDate(days[0].date).getUTCDay() + 6) % 7 : 0;
  const weekCount = Math.ceil((firstWeekday + days.length) / 7);
  const months: { label: string; column: number }[] = [];
  days.forEach((day, index) => {
    const date = calendarDate(day.date);
    if (date.getUTCDate() === 1 || index === 0) {
      const column = Math.floor((firstWeekday + index) / 7) + 1;
      // 首周不足一个月时，避免两个相邻月份标题挤在一起。
      if (months.length && column - months[months.length - 1].column < 3) {
        months.pop();
      }
      months.push({label: new Intl.DateTimeFormat(locale, {month: "short", timeZone: "UTC"}).format(date), column});
    }
  });
  return {firstWeekday, weekCount, months};
}

export function nextActivityIndex(index: number, key: string, length: number) {
  const movement: Record<string, number> = {ArrowLeft: -7, ArrowRight: 7, ArrowUp: -1, ArrowDown: 1};
  if (key === "Home") {
    return 0;
  }
  if (key === "End") {
    return Math.max(0, length - 1);
  }
  return Math.max(0, Math.min(length - 1, index + (movement[key] ?? 0)));
}

/** 跨天后日期的位置会前移，按日期保留用户选择，不使用旧数组位置。 */
export function selectedActivityIndex(days: ActivityDay[], date: string | null) {
  const index = days.findIndex((day) => day.date === date);
  return index >= 0 ? index : days.length - 1;
}
