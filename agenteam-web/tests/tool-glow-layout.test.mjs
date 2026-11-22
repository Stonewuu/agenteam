import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/features/agent/lib/tool-glow-layout.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022},
}).outputText;
const {toolGlowLayout} = await import("data:text/javascript;base64," + Buffer.from(compiled).toString("base64"));
const layer = {left: 80, top: 160, width: 400, height: 300};
const viewport = {left: 100, top: 200, width: 300, height: 200};
const card = {left: 110, top: 210, width: 200, height: 60};

test("首尾卡片完整可见时，光效四边均可超出滚动区域", () => {
  const atTop = {...card, top: viewport.top};
  const atBottom = {...card, top: viewport.top + viewport.height - card.height};
  for (const value of [atTop, atBottom]) {
    const layout = toolGlowLayout(value, layer, [viewport], 38);
    assert.equal(layout.clipPath, "inset(-38px -38px -38px -38px)");
    assert.equal(layout.height, card.height);
    assert.equal(layout.top, value.top - layer.top);
  }
});

test("部分滚出的卡片只绘制仍然可见的边，不在列表外残留被遮住的边框", () => {
  assert.equal(toolGlowLayout({...card, top: 170}, layer, [viewport], 38).clipPath,
    "inset(30px -38px -38px -38px)");
  assert.equal(toolGlowLayout({...card, top: 370}, layer, [viewport], 38).clipPath,
    "inset(-38px -38px 30px -38px)");
  assert.equal(toolGlowLayout({...card, left: 70}, layer, [viewport], 38).clipPath,
    "inset(-38px -38px -38px 30px)");
});

test("滚出视口、零尺寸和收起的列表不绘制光效", () => {
  assert.equal(toolGlowLayout({...card, top: 140}, layer, [viewport], 38), null);
  assert.equal(toolGlowLayout({...card, top: 400}, layer, [viewport], 38), null);
  assert.equal(toolGlowLayout({...card, width: 0}, layer, [viewport], 38), null);
  assert.equal(toolGlowLayout(card, layer, [{...viewport, height: 0}], 38), null);
});

test("嵌套列表同时遵守内外两层的可见范围", () => {
  const inner = {left: 120, top: 240, width: 180, height: 80};
  assert.equal(toolGlowLayout({...card, height: 150}, layer, [viewport, inner], 38).clipPath,
    "inset(30px 10px 40px 10px)");
  assert.equal(toolGlowLayout(card, layer, [viewport, {...inner, top: 450}], 38), null);
});

test("页面整体移动不改变光效相对位置，卡片展开后按新尺寸绘制", () => {
  const move = (rect) => ({...rect, left: rect.left + 25, top: rect.top - 60});
  assert.deepEqual(toolGlowLayout(move(card), move(layer), [move(viewport)], 38),
    toolGlowLayout(card, layer, [viewport], 38));
  const expanded = toolGlowLayout({...card, width: 220, height: 160}, layer, [viewport], 38);
  assert.equal(expanded.width, 220);
  assert.equal(expanded.height, 160);
  assert.equal(expanded.clipPath, "inset(-38px -38px -38px -38px)");
});
