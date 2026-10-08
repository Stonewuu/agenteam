import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import ts from "typescript";

const require = createRequire(import.meta.url);
const { selectToolCallDetails } = require("../.test-build/tool-payload/tool-call-details.js");
const display = (input, result) => selectToolCallDetails(JSON.stringify(input), JSON.stringify(result));
const find = (view, part, key) => view.fields.find((field) => field.part === part && field.key === key);
const fixtureCode = ts.transpileModule(readFileSync(new URL("./fixtures/tool-call-detail-cases.ts", import.meta.url), "utf8"), {
  compilerOptions: { module: ts.ModuleKind.ES2022, target: ts.ScriptTarget.ES2022 },
}).outputText;
const { toolCallDetailCases } = await import(`data:text/javascript;base64,${Buffer.from(fixtureCode).toString("base64")}`);

test("浏览器验收场景中的正文、修改前后、命令和查询结果都直接呈现", () => {
  const expected = {
    create: ["input:path", "input:content"],
    edit: ["input:path", "input:old_text", "input:new_text"],
    command: ["input:command", "result:stdout"],
    failed: ["input:command", "result:stderr", "result:exitCode"],
    search: ["input:query", "result:items"],
    empty: ["input:fields", "input:filters", "result:rows"],
  };
  for (const example of toolCallDetailCases) {
    const view = display(example.input, example.result);
    for (const field of expected[example.id]) {
      assert.ok(view.fields.some((item) => `${item.part}:${item.key}` === field), `${example.id} 应直接展示 ${field}`);
    }
  }
});

test("创建文件直接展示完整正文，输出中的相同路径只出现一次", () => {
  const content = Array.from({ length: 120 }, (_, index) => `第 ${index + 1} 行：保留原文及缩进`).join("\n");
  const view = display({ path: "work/report.md", content }, { path: "work/report.md", sizeBytes: 9000, revision: "2" });
  assert.equal(find(view, "input", "path").value, "work/report.md");
  assert.equal(find(view, "input", "content").value, content);
  assert.equal(find(view, "input", "content").code, true);
  assert.equal(find(view, "result", "path"), undefined);
  assert.deepEqual(view.additional.result, { sizeBytes: 9000, revision: "2" });
});

test("编辑文件同时呈现修改前后，删除全部文字时也保留空的新内容", () => {
  const view = display({ path: "work/app.py", old_text: "print('旧内容')\n", new_text: "", replace_all: true }, { path: "work/app.py", replacements: 2 });
  assert.equal(find(view, "input", "old_text").label, "修改前");
  assert.equal(find(view, "input", "old_text").value, "print('旧内容')\n");
  assert.equal(find(view, "input", "new_text").label, "修改后");
  assert.equal(find(view, "input", "new_text").value, "");
  assert.deepEqual(view.additional.input, { replace_all: true });
  assert.deepEqual(view.additional.result, { replacements: 2 });
});

test("命令与完整输出直接显示，正常退出码和执行选项放到更多内容", () => {
  const command = "python work/report.py\nls -la outputs/";
  const stdout = "输出内容\n".repeat(100);
  const view = display({ command, working_directory: "work", timeout_seconds: 60 }, {
    exitCode: 0, stdout, stderr: "", stdoutPath: "tool-results/out.txt", stdoutBytes: 2000,
  });
  assert.equal(find(view, "input", "command").value, command);
  assert.equal(find(view, "result", "stdout").value, stdout);
  assert.equal(find(view, "result", "exitCode"), undefined);
  assert.equal(find(view, "result", "stderr"), undefined);
  assert.equal(view.additional.result.exitCode, 0);
  assert.equal(view.additional.input.timeout_seconds, 60);
});

test("错误输出、非零退出码、超时和真实错误标志均不能隐藏", () => {
  const view = display({ command: "python work/missing.py" }, {
    exitCode: 2, stdout: "", stderr: "文件不存在", timedOut: true, capacityExceeded: true, isError: true,
  });
  assert.equal(find(view, "result", "stderr").value, "文件不存在");
  for (const key of ["exitCode", "timedOut", "capacityExceeded", "isError"]) {
    assert.equal(find(view, "result", key).error, true);
  }
  assert.equal(find(view, "result", "stdout"), undefined);
  assert.equal(view.additional.result.stdout, "");
  assert.equal(find(view, "result", "timedOut").notice, "命令执行超时。");
});

test("文件修改失败的说明不能被当成读取到的文件正文", () => {
  const view = display({ path: "work/app.py", old_text: "旧内容", new_text: "新内容" }, { isError: true, content: "未找到需要替换的原文。" });
  const field = find(view, "result", "content");
  assert.equal(field.label, "错误信息");
  assert.equal(field.error, true);
  assert.equal(field.code, false);
  assert.equal(field.value, "未找到需要替换的原文。");
});

test("读取、搜索和数据查询保留正文与结果，包括真实空结果", () => {
  const read = display({ path: "work/readme.md", start_line: 5 }, { path: "work/readme.md", content: "第五行\n第六行", endLine: 6 });
  assert.equal(find(read, "result", "content").value, "第五行\n第六行");
  const search = display({ query: "预算", filters: [{ field: "year", value: "2026" }], limit: 20 }, { rows: [], total: 0, nextCursor: null });
  assert.equal(find(search, "input", "query").value, "预算");
  assert.deepEqual(find(search, "input", "filters").value, [{ field: "year", value: "2026" }]);
  assert.deepEqual(find(search, "result", "rows").value, []);
  assert.equal(search.additional.result.total, 0);
});

test("计划工具不因只取几个字段而遗漏任务内容、时区或执行时间", () => {
  const view = display({ name: "周报", inputText: "整理本周工作", frequency: "weekly", weekdays: [5], localTime: "18:00", timezone: "Asia/Shanghai", enabled: false, maxRetries: 2 }, { name: "周报", nextRunAt: null });
  for (const key of ["name", "inputText", "frequency", "weekdays", "localTime", "timezone", "enabled"]) {
    assert.ok(find(view, "input", key));
  }
  assert.equal(find(view, "input", "enabled").value, false);
  assert.equal(view.additional.input.maxRetries, 2);
});

test("上下文工具保留企业和成员，数据查询保留真实字段标签", () => {
  const context = display({}, { enterprise: "石头工作室", user: "石头", timezone: "Asia/Shanghai" });
  assert.equal(find(context, "result", "enterprise").value, "石头工作室");
  assert.equal(find(context, "result", "user").value, "石头");
  const columns = [{ name: "amount", label: "金额" }];
  const query = display({ fields: ["amount"] }, { rows: [{ amount: "120.50" }], fields: columns });
  assert.equal(find(query, "input", "fields").label, "查询字段");
  assert.deepEqual(find(query, "result", "rows").columns, columns);
});

test("未知工具保留原值，附加字段可以找到，不修改原输入", () => {
  const input = { userSelection: ["甲", "乙"], revision: "5", customFlag: false };
  const original = JSON.stringify(input);
  const view = display(input, { vendorValue: 0, customResult: null, id: "item-1" });
  assert.equal(JSON.stringify(input), original);
  assert.deepEqual(find(view, "input", "userSelection").value, ["甲", "乙"]);
  assert.equal(find(view, "input", "customFlag").value, false);
  assert.equal(find(view, "result", "vendorValue").value, 0);
  assert.equal(find(view, "result", "customResult").value, null);
  assert.equal(view.additional.result.id, "item-1");
  const known = display({ query: "内容", vendorOption: false }, { content: "结果", vendorCode: "x" });
  assert.equal(known.additional.input.vendorOption, false);
  assert.equal(known.additional.result.vendorCode, "x");
});

test("外部工具文本包装解开后仍保留错误和附加信息，不显示重复正文", () => {
  const body = { stdout: "完整结果", exitCode: 1 };
  const view = display({}, { content: [{ type: "text", text: JSON.stringify(body) }], structuredContent: body, isError: true, metadata: { revision: "2" } });
  assert.equal(find(view, "result", "stdout").value, "完整结果");
  assert.equal(find(view, "result", "isError").value, true);
  assert.equal(find(view, "result", "exitCode").value, 1);
  assert.deepEqual(view.additional.result.metadata, { revision: "2" });
  const different = display({}, { content: [{ type: "text", text: "文字结果" }], structuredContent: { value: 123 } });
  assert.ok(find(different, "result", "content"));
  assert.ok(find(different, "result", "structuredContent"));
});

test("截取标志和后续结果提醒保持可见，不把部分数据当作完整结果", () => {
  const view = display({}, { content: "已读取部分结果", truncated: true, hasMore: true, nextCursor: "cursor" });
  assert.equal(find(view, "result", "truncated").value, true);
  assert.equal(find(view, "result", "hasMore").value, true);
  assert.equal(view.additional.result.nextCursor, "cursor");
});

test("纯文本、数组、未完成结构及特殊字段名称不会丢失或污染对象", () => {
  const plain = selectToolCallDetails("输入原文", "结果原文\n第二行");
  assert.equal(plain.fields[1].value, "结果原文\n第二行");
  const array = display({}, [1, false, null]);
  assert.deepEqual(array.fields[0].value, [1, false, null]);
  const partial = selectToolCallDetails('{"content":"尚未', "");
  assert.equal(partial.fields[0].value, '{"content":"尚未');
  const special = selectToolCallDetails('{"query":"内容","__proto__":{"changed":true},"constructor":"保留"}', "");
  assert.equal(Object.hasOwn(special.additional.input, "__proto__"), true);
  assert.equal(special.additional.input.constructor, "保留");
  assert.equal({}.changed, undefined);
  const nullValue = display({}, { content: [{ type: "text", text: "null" }] });
  assert.equal(nullValue.fields[0].value, null);
});
