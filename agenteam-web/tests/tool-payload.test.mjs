import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { parseToolPayload, formatRawToolPayload, payloadFieldLabel, formatPayloadScalar, payloadColumns, payloadTextPart, payloadEntries, isAdditionalPayloadField } = require("../.test-build/tool-payload/tool-payload-format.js");

test("完整解析对象、数组和基本值，保留空值、否、零和空文本的区别", () => {
  assert.deepEqual(parseToolPayload('{"a":false,"b":0,"c":null,"d":"","e":[]}'), { a: false, b: 0, c: null, d: "", e: [] });
  assert.deepEqual(parseToolPayload('[1,"文字",false,null]'), [1, "文字", false, null]);
  assert.deepEqual([null, undefined, false, 0, ""].map((value) => formatPayloadScalar(value)), ["空值", "未提供", "否", "0", "空文本"]);
});

test("未完成的参数和普通文本保持原样，不能因解析失败丢失内容", () => {
  for (const source of ['{"name":"尚未', "网络请求未完成\n请稍后再试", "", "<script>alert(1)</script>"]) {
assert.equal(parseToolPayload(source), source);
}
});

test("原始模式只调整缩进，大整数、小数、重复字段和字符串转义均保留原文", () => {
  const source = String.raw`{"id":9223372036854775807,"amount":0.10000000000000000001,"id":1e+19,"text":"带有 \\\" 和逗号,的文本\\n","empty":[{},[]],"nil":null}`;
  const formatted = formatRawToolPayload(source);
  const literals = (text) => text.match(/"(?:\\[\s\S]|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null|[{}\[\],:]/g);
  assert.deepEqual(literals(formatted), literals(source));
  assert.match(formatted, /\n  "id": 9223372036854775807/);
  assert.equal(formatRawToolPayload('{"partial":'), '{"partial":');
  assert.equal(formatRawToolPayload("普通文本"), "普通文本");
  assert.equal(formatRawToolPayload("{}"), "{}");
  assert.equal(formatRawToolPayload("[]"), "[]");
  assert.equal(formatRawToolPayload("false"), "false");
  assert.equal(formatRawToolPayload('"文本"'), '"文本"');
  const deeplyNested = "[".repeat(70) + "0" + "]".repeat(70);
  assert.equal(formatRawToolPayload(deeplyNested), deeplyNested);
});

test("大整数编号和超出数值范围的内容不显示为四舍五入的编号或无穷大", () => {
  const source = '{"id":9223372036854775807,"value":1e999}';
  const result = parseToolPayload(source);
  // 支持 JSON 原文参数的浏览器保留数值原文，其余浏览器保留完整调用文本。
  if (typeof result === "string") {
assert.equal(result, source);
} else {
assert.deepEqual(result, { id: "9223372036854775807", value: "1e999" });
}
});

test("只转换确定的字段和值，未知字段、未知状态及对象原型名称不被错误解释", () => {
  assert.equal(payloadFieldLabel("inputText"), "任务内容");
  assert.equal(payloadFieldLabel("operator"), "匹配条件");
  assert.equal(formatPayloadScalar("eq", "operator"), "等于");
  assert.equal(formatPayloadScalar("desc", "direction"), "降序");
  for (const key of ["custom_score", "constructor", "__proto__"]) {
assert.equal(payloadFieldLabel(key), key);
}
  assert.equal(formatPayloadScalar("once", "frequency"), "仅一次");
  assert.equal(formatPayloadScalar("once", "custom_frequency"), "once");
  assert.equal(formatPayloadScalar("vendor_pending", "status"), "vendor_pending");
  assert.equal(formatPayloadScalar("constructor", "status"), "constructor");
});

test("时间使用明确的时区，同一时刻在上海和纽约显示正确，日期不偏移", () => {
  const instant = "2026-09-19T07:00:00Z";
  assert.equal(formatPayloadScalar(instant, "nextRunAt", "Asia/Shanghai"), "2026/09/19 15:00:00（Asia/Shanghai）");
  assert.equal(formatPayloadScalar(instant, "nextRunAt", "America/New_York"), "2026/09/19 03:00:00（America/New_York）");
  assert.equal(formatPayloadScalar(instant, "nextRunAt", "invalid/zone"), instant);
  assert.equal(formatPayloadScalar(instant, "name", "Asia/Shanghai"), instant);
  assert.equal(formatPayloadScalar("2026-09-19", "localDate", "America/New_York"), "2026-09-19");
});

test("表格保留全部行的字段，采用真实字段标签，缺失字段与空值仍可区分", () => {
  const rows = [{ name: "甲", amount: 0 }, { name: "乙", amount: null, enabled: false }];
  assert.deepEqual(payloadColumns(rows, [{ name: "amount", label: "金额" }]), [
    { key: "name", label: "名称" }, { key: "amount", label: "金额" }, { key: "enabled", label: "是否启用" },
  ]);
  assert.equal(rows[0].enabled, undefined);
  assert.deepEqual(rows[1], { name: "乙", amount: null, enabled: false });
});

test("嵌套对象、混合数组和过宽表格改用条目展示，避免删减信息来凑表格", () => {
  for (const rows of [[], [1, { name: "甲" }], [{ nested: { x: 1 } }], [Object.fromEntries(Array.from({ length: 9 }, (_, i) => [`字段${i}`, i]))]]) {
assert.equal(payloadColumns(rows), null);
}
  assert.equal(payloadColumns([{ id: "abc", name: "甲" }]), null);
  assert.ok(payloadColumns([{ id: "abc", name: "甲" }], [{ name: "id", label: "业务编号" }]));
});

test("标准文本块可直接阅读，带注解、图片或其他信息的块不能丢失附加字段", () => {
  assert.equal(payloadTextPart({ type: "text", text: '{"name":"示例"}' }), '{"name":"示例"}');
  for (const part of [{ type: "text", text: "示例", annotations: { audience: ["user"] } }, { type: "image", data: "abc" }, { text: "没有类型" }]) {
assert.equal(payloadTextPart(part), null);
}
});

test("常用信息优先展示但不修改原对象，也不省略未知或分页字段", () => {
  const value = { revision: "2", custom: false, id: "record-1", name: "示例", nextCursor: "next-page" };
  const original = JSON.stringify(value);
  const entries = payloadEntries(value);
  assert.equal(entries[0][0], "name");
  assert.deepEqual(Object.fromEntries(entries), value);
  assert.equal(JSON.stringify(value), original);
  assert.equal(isAdditionalPayloadField("nextCursor"), true);
  assert.equal(isAdditionalPayloadField("unknownId"), false);
});

test("截取结果的附件、失败标志、未知字段和大列表都完整保留", () => {
  const value = { truncated: true, fileId: "file-1", content: "部分内容", isError: true, vendorCode: "E12", items: Array.from({ length: 105 }, (_, index) => ({ index })) };
  assert.deepEqual(parseToolPayload(JSON.stringify(value)), value);
  assert.equal(parseToolPayload(JSON.stringify(value)).items.length, 105);
});

test("外部工具的完全相同文本只显示一次，原对象和确认参数仍保留全部字段", () => {
  const text = "## 实际结果\n\n第一条结论";
  const source = { content: [{ type: "text", text }], structuredContent: { result: text }, isError: false };
  const original = JSON.stringify(source);
  assert.deepEqual(payloadEntries(source).map(([key]) => key), ["content", "isError"]);
  assert.equal(payloadEntries(source, true).length, 3);
  assert.equal(JSON.stringify(source), original);
  assert.equal(payloadEntries({ ...source, structuredContent: { result: text, additional: "额外信息" } }).length, 3);
  assert.equal(payloadEntries({ ...source, structuredContent: { result: `${text}，更多内容` } }).length, 3);
  const structured = { name: "结果", count: 0 };
  assert.equal(payloadEntries({ content: [{ type: "text", text: JSON.stringify(structured) }], structuredContent: structured }).length, 1);
});
