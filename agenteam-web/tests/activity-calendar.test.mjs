import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/workspace/lib/activity-calendar.ts", import.meta.url), "utf8");
const exports = {};
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, { exports });
const { activityTotal, activityLevel, activityCalendar, calendarDate, nextActivityIndex, selectedActivityIndex } = exports;

function yearEnding(end) {
  const last = new Date(`${end}T00:00:00Z`);
  const start = new Date(last);
  start.setUTCFullYear(start.getUTCFullYear() - 1);
  start.setUTCDate(start.getUTCDate() + 1);
  const days = [];
  for (const date = start; date <= last; date.setUTCDate(date.getUTCDate() + 1)) {
    days.push({ date: date.toISOString().slice(0, 10), conversations: 0, schedules: 0, todos: 0, employees: 0 });
  }
  return days;
}

test("普通年与闰年的每一天只出现一次，并完整保留首尾不足一周的日期", () => {
  for (const end of ["2026-09-23", "2024-12-31", "2024-12-29", "2025-01-01"]) {
    const days = yearEnding(end);
    const { firstWeekday, weekCount, months } = activityCalendar(days);
    assert.ok(days.length === 365 || days.length === 366);
    assert.equal(new Set(days.map((day) => day.date)).size, days.length);
    assert.equal(days.at(-1).date, end);
    const occupied = new Set(days.map((_, index) => `${Math.floor((firstWeekday + index) / 7)}:${(firstWeekday + index) % 7}`));
    assert.equal(occupied.size, days.length);
    assert.ok(firstWeekday + days.length <= weekCount * 7);
    assert.ok(months.every((month) => month.column >= 1 && month.column <= weekCount));
    if (end.startsWith("2024")) {
      assert.ok(days.some((day) => day.date === "2024-02-29"));
    }
  }
});

test("计数只来自四类真实活动，颜色随活动次数增加而加深", () => {
  assert.equal(activityTotal({ conversations: 4, schedules: 3, todos: 2, employees: 1 }), 10);
  assert.equal(activityLevel(0, 0), 0);
  assert.equal(activityLevel(1, 1), 4);
  let last = 0;
  for (let count = 0; count <= 100; count += 1) {
    const level = activityLevel(count, 100);
    assert.ok(level >= last && level <= 4);
    if (count > 0) {
      assert.ok(level > 0);
    }
    last = level;
  }
});

test("键盘按周左右移动，按天上下移动，首尾不会越界", () => {
  assert.equal(nextActivityIndex(10, "ArrowLeft", 365), 3);
  assert.equal(nextActivityIndex(10, "ArrowRight", 365), 17);
  assert.equal(nextActivityIndex(10, "ArrowUp", 365), 9);
  assert.equal(nextActivityIndex(10, "ArrowDown", 365), 11);
  assert.equal(nextActivityIndex(0, "ArrowLeft", 365), 0);
  assert.equal(nextActivityIndex(364, "ArrowRight", 365), 364);
  assert.equal(nextActivityIndex(25, "Home", 365), 0);
  assert.equal(nextActivityIndex(25, "End", 365), 364);
  assert.equal(calendarDate("2026-09-23").toISOString(), "2026-09-23T00:00:00.000Z");
});

test("跨天更新保留已选择的日期，首次打开和超出范围时选择最近一天", () => {
  const before = yearEnding("2026-09-22");
  const after = yearEnding("2026-09-23");
  const selected = "2026-06-01";
  assert.equal(after[selectedActivityIndex(after, selected)].date, selected);
  assert.equal(selectedActivityIndex(after, selected), selectedActivityIndex(before, selected) - 1);
  assert.equal(selectedActivityIndex(after, null), after.length - 1);
  assert.equal(selectedActivityIndex(after, "2020-01-01"), after.length - 1);
});
