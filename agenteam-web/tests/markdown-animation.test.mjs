import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";
import { createAnimatePlugin } from "streamdown";

const load = createRequire(import.meta.url);
const { compactAnimationNodes } = load("../.test-build/markdown-animation/markdown-animation-nodes.js");

function paragraph(text) {
  return { type: "root", children: [{ type: "element", tagName: "p", properties: {}, children: [{ type: "text", value: text }] }] };
}

function animator() {
  const plugin = createAnimatePlugin({ animation: "fadeIn", sep: "char", stagger: 0, duration: 180 });
  const transform = plugin.rehypePlugin();
  return (tree) => {
    transform(tree);
    compactAnimationNodes()(tree);
    plugin.commit();
    return tree;
  };
}

function textContent(node) {
  if (node.type === "text") {
    return node.value;
  }
  return (node.children ?? []).map(textContent).join("");
}

function spans(node) {
  return [...(node.type === "element" && node.tagName === "span" ? [node] : []), ...(node.children ?? []).flatMap(spans)];
}

test("长中文同批文字完整显示，只保留一个同时淡入的元素", () => {
  const text = "文字应当及时显示，不必等待动画。".repeat(500);
  const tree = animator()(paragraph(text));
  assert.equal(textContent(tree), text);
  assert.equal(spans(tree).length, 1);
  assert.match(spans(tree)[0].properties.style, /--sd-duration:180ms/);
  assert.doesNotMatch(spans(tree)[0].properties.style, /--sd-delay/);
});

test("连续追加时旧文字还原为文本，新批次有独立起点且不丢字", () => {
  const render = animator();
  render(paragraph("前一批文字"));
  const next = render(paragraph("前一批文字本批文字"));
  assert.deepEqual(next.children[0].children[0], { type: "text", value: "前一批文字" });
  assert.equal(spans(next).length, 1);
  assert.equal(spans(next)[0].properties["data-stream-animation-start"], "前一批文字".length);
  assert.equal(textContent(spans(next)[0]), "本批文字");
  const last = render(paragraph("前一批文字本批文字最后一批"));
  assert.equal(textContent(last), "前一批文字本批文字最后一批");
  assert.equal(spans(last)[0].properties["data-stream-animation-start"], "前一批文字本批文字".length);
  assert.equal(textContent(spans(last)[0]), "最后一批");
});

test("相同内容重复转换时不重复动画，不保留逐字包裹", () => {
  const render = animator();
  const text = "已经显示的完整内容";
  render(paragraph(text));
  const tree = render(paragraph(text));
  assert.equal(spans(tree).length, 0);
  assert.equal(textContent(tree), text);
});

test("中文、空格、换行及表情符号保持原文", () => {
  const render = animator();
  const original = "中文 English 👨‍👩‍👧‍👦\n  保留空白\t";
  render(paragraph(original));
  const tree = render(paragraph(original + "继续👋🏽 é"));
  assert.equal(textContent(tree), original + "继续👋🏽 é");
  assert.ok(spans(tree).length < 8);
});

test("合并不跨链接和强调边界，代码块及复制内容保持完整", () => {
  const link = { type: "element", tagName: "a", properties: { href: "https://example.com" }, children: [{ type: "text", value: "链接文字" }] };
  const strong = { type: "element", tagName: "strong", properties: {}, children: [{ type: "text", value: "重点文字" }] };
  const pre = { type: "element", tagName: "pre", properties: {}, children: [{ type: "element", tagName: "code", properties: { className: ["language-js"] }, children: [{ type: "text", value: "const value = '中文';\n" }] }] };
  const original = structuredClone(pre);
  const tree = { type: "root", children: [{ type: "element", tagName: "p", properties: {}, children: [{ type: "text", value: "正文" }, link, strong] }, pre] };
  animator()(tree);
  assert.equal(link.properties.href, "https://example.com");
  assert.equal(textContent(link), "链接文字");
  assert.equal(textContent(strong), "重点文字");
  assert.deepEqual(pre, original);
  assert.equal(spans(tree).length, 3);
});

test("不同时间的动画不能合并，带额外属性的元素保持原样", () => {
  const animated = (style, properties = {}) => ({ type: "element", tagName: "span", properties: { "data-sd-animate": true, style, ...properties }, children: [{ type: "text", value: "文字" }] });
  const first = animated("--sd-duration:180ms;--sd-delay:0ms");
  const second = animated("--sd-duration:180ms;--sd-delay:20ms");
  const custom = animated("--sd-duration:0ms", { className: ["custom"] });
  const tree = { type: "root", children: [first, second, custom] };
  compactAnimationNodes()(tree);
  assert.equal(tree.children.length, 3);
  assert.equal(tree.children[2], custom);
  assert.equal(textContent(tree), "文字文字文字");
});

test("已有纯文本和静态格式不受影响", () => {
  const tree = paragraph("历史消息无须播放动画。");
  const original = structuredClone(tree);
  compactAnimationNodes()(tree);
  assert.deepEqual(tree, original);
});
