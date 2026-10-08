import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const exports = {};
const source = readFileSync(new URL("../src/features/agent/lib/composer-selection.ts", import.meta.url), "utf8");
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, { exports });
const { readComposerCommand } = exports;

test("斜杠和 @ 命令保留文字位置及当前搜索词", () => {
  for (const marker of ["/", "@"]) {
    const text = `请帮我 ${marker}工作`;
    const command = readComposerCommand(text, text.length);
    assert.equal(command.marker, marker);
    assert.equal(command.query, "工作");
    assert.equal(text.slice(command.start, command.end), `${marker}工作`);
  }
});

test("删除命令或移出命令范围后不再匹配", () => {
  assert.equal(readComposerCommand("", 0), null);
  assert.equal(readComposerCommand("/工作 ", 4), null);
  assert.equal(readComposerCommand("说明 /工作", 2), null);
  assert.equal(readComposerCommand("/@", 2), null);
});

test("链接和邮箱中的符号不打开命令选择", () => {
  for (const text of ["https://example.com", "name@example.com", "目录/文件", "普通文字"]) {
    assert.equal(readComposerCommand(text, text.length), null);
  }
});

test("在消息中间编辑命令时不会消耗后面的正文", () => {
  const text = "说明 /工作 后续内容";
  const command = readComposerCommand(text, 6);
  assert.equal(command.text, "/工作");
  assert.equal(text.slice(0, command.start) + text.slice(command.end), "说明  后续内容");
});
