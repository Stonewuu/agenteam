import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/features/agent/lib/conversation-sidebar-layout.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { sidebarDragWidth, sidebarLayout } = await import("data:text/javascript;base64," + Buffer.from(compiled).toString("base64"));

test("普通屏幕按 400 像素边界进入全宽，边界本身保持并排", () => {
  assert.equal(sidebarLayout(800, 1200).full, false);
  assert.deepEqual(sidebarLayout(801, 1200), { full: true, width: 1200, maximumSplitWidth: 800 });
});

test("宽屏按对话区域的 20% 判定，允许侧栏超过旧的固定宽度", () => {
  assert.equal(sidebarLayout(2400, 3000).full, false);
  assert.equal(sidebarLayout(2401, 3000).width, 3000);
  assert.equal(sidebarLayout(1200, 2400).width, 1200);
});

test("恢复对话沿用并排宽度，窄侧栏仍有可操作的最小宽度", () => {
  assert.equal(sidebarLayout(520, 1440, true).width, 1440);
  assert.equal(sidebarLayout(520, 1440, false).width, 520);
  assert.equal(sidebarLayout(-20, 1440).width, 280);
});

test("拖过撑满边界时保持鼠标指定的宽度，松开才决定是否撑满", () => {
  assert.equal(sidebarDragWidth(950, 1200), 950);
  assert.equal(sidebarLayout(sidebarDragWidth(950, 1200), 1200).width, 1200);
});

test("从全宽往回拖会立即缩小，松开后才保留并排或回到全宽", () => {
  assert.equal(sidebarDragWidth(1200 - 100, 1200), 1100);
  assert.equal(sidebarLayout(1100, 1200).full, true);
  assert.equal(sidebarDragWidth(1200 - 500, 1200), 700);
  assert.equal(sidebarLayout(700, 1200).full, false);
  assert.equal(sidebarDragWidth(1300, 1200), 1200);
});
