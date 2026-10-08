import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/agent/lib/conversation-date-groups.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const exports = {};
vm.runInNewContext(compiled, { exports, Intl, Date });
const { groupConversationsByDate } = exports;

function groups(dates, timezone, now) {
  const items = dates.map((updatedAt, id) => ({ id, updatedAt }));
  return JSON.parse(JSON.stringify(groupConversationsByDate(items, timezone, new Date(now))))
    .map(({ key, items: conversations }) => ({ key, ids: conversations.map(({ id }) => id) }));
}

test("按企业时区分组，跨日后保持同组对话原有顺序", () => {
  assert.deepEqual(groups([
    "2026-09-20T01:00:00Z", "2026-09-19T17:00:00Z", "2026-09-19T15:59:59Z", "2026-09-17T16:00:00Z",
  ], "Asia/Shanghai", "2026-09-20T02:00:00Z"), [
    { key: "today", ids: [0, 1] }, { key: "yesterday", ids: [2] }, { key: "earlier", ids: [3] },
  ]);
});

test("跨年时仍能识别昨天", () => {
  assert.deepEqual(groups(["2026-12-31T15:59:59Z"], "Asia/Shanghai", "2026-12-31T16:00:00Z"), [
    { key: "yesterday", ids: [0] },
  ]);
});

test("夏令时开始时按当地日历识别今天和昨天", () => {
  assert.deepEqual(groups([
    "2026-03-09T04:15:00Z", "2026-03-08T05:15:00Z", "2026-03-08T04:59:59Z",
  ], "America/New_York", "2026-03-09T04:30:00Z"), [
    { key: "today", ids: [0] }, { key: "yesterday", ids: [1] }, { key: "earlier", ids: [2] },
  ]);
});

test("夏令时结束时一天超过 24 小时仍属于昨天", () => {
  assert.deepEqual(groups(["2026-11-01T04:15:00Z"], "America/New_York", "2026-11-02T05:30:00Z"), [
    { key: "yesterday", ids: [0] },
  ]);
});

test("午夜后根据新的日期重新分组", () => {
  const dates = ["2026-09-19T16:00:00Z"];
  assert.deepEqual(groups(dates, "Asia/Shanghai", "2026-09-20T15:59:59Z"), [{ key: "today", ids: [0] }]);
  assert.deepEqual(groups(dates, "Asia/Shanghai", "2026-09-20T16:00:00Z"), [{ key: "yesterday", ids: [0] }]);
});

test("空列表不显示分组，也不把未来或无效日期标为更早", () => {
  assert.deepEqual(groups([], "Asia/Shanghai", "2026-09-20T02:00:00Z"), []);
  assert.deepEqual(groups(["2026-09-21T02:00:00Z", "无效日期"], "Asia/Shanghai", "2026-09-20T02:00:00Z"), [
    { key: "other", ids: [0, 1] },
  ]);
});
