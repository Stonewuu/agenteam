import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/plugin/api/tool-content.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
function reader(responses) {
  const requests = [];
  const exports = {};
  vm.runInNewContext(compiled, { exports, URLSearchParams, require: () => ({ apiRequest: async (url, options) => {
    requests.push({ url, ...options });
    const response = responses.shift();
    return typeof response === "function" ? response() : response;
  } }) });
  return { read: exports.readToolContentPreview, requests };
}
const location = { url: "/api/v1/example/tool-content", part: "input", revision: "completed" };
function window(content, startOffset, endOffset, sizeBytes, eof, revision = "v1") {
  return { content, startOffset, endOffset, sizeBytes, eof, revision, path: "input", totalLines: 8,
    startLine: 1, endLine: 8, rangeComplete: eof, nextCursor: eof ? null : String(endOffset) };
}

test("较长正文在既有内存预算内补齐，并固定第一次返回的版本", async () => {
  const { read, requests } = reader([window("前半段", 0, 32768, 90000, false), window("后半段", 32768, 90000, 90000, true)]);
  const value = await read(location, false, new AbortController().signal);
  assert.equal(value.content, "前半段后半段");
  assert.equal(value.eof, true);
  assert.equal(value.startOffset, 0);
  assert.equal(requests.length, 2);
  const query = new URL(requests[1].url, "http://localhost").searchParams;
  assert.equal(query.get("offset"), "32768");
  assert.equal(query.get("revision"), "v1");
  assert.equal(query.get("limit"), "57232");
});

test("超过预算的内容只读取首段，交给原有连续阅读组件", async () => {
  const { read, requests } = reader([window("首段", 0, 32768, 5000000, false)]);
  const value = await read(location, false, new AbortController().signal);
  assert.equal(value.eof, false);
  assert.equal(requests.length, 1);
});

test("完整的短内容不会发出额外请求", async () => {
  const { read, requests } = reader([window("内容", 0, 6, 6, true)]);
  assert.equal((await read(location, false, new AbortController().signal)).content, "内容");
  assert.equal(requests.length, 1);
});

test("只剩结束符时仍使用接口允许的最小读取范围", async () => {
  const { read, requests } = reader([window("正文", 0, 32768, 32769, false), window("}", 32768, 32769, 32769, true)]);
  assert.equal((await read(location, false, new AbortController().signal)).content, "正文}");
  assert.equal(new URL(requests[1].url, "http://localhost").searchParams.get("limit"), "4");
});

test("收起详情取消请求后不继续补读", async () => {
  const controller = new AbortController();
  const { read, requests } = reader([() => {
    controller.abort();
    return window("首段", 0, 32768, 90000, false);
  }]);
  await assert.rejects(read(location, false, controller.signal), { name: "AbortError" });
  assert.equal(requests.length, 1);
});

test("版本、位置或长度变化时不把两份内容错误合并", async () => {
  for (const remainder of [window("后半段", 32768, 90000, 90000, true, "v2"),
    window("后半段", 32000, 90000, 90000, true), window("后半段", 32768, 89000, 90000, false),
    window("后半段", 32768, 90000, 100000, true)]) {
    const { read } = reader([window("前半段", 0, 32768, 90000, false), remainder]);
    await assert.rejects(read(location, false, new AbortController().signal), /内容已经变化/);
  }
});
