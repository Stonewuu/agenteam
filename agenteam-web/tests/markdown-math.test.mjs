import test from "node:test";
import assert from "node:assert/strict";
import {createElement} from "react";
import {renderToStaticMarkup} from "react-dom/server";
import {Streamdown} from "streamdown";
import {createMathPlugin} from "@streamdown/math";

test("数学渲染依赖更新后，行内分数和独立公式保留完整排版", () => {
  const plugins = {math: createMathPlugin({singleDollarTextMath: true})};
  for (const [content, display] of [
    ["中文说明：$\\frac{1}{2}$。", false],
    ["$$\n\\sum_{k=1}^{n}k=\\frac{n(n+1)}{2}\n$$", true],
  ]) {
    const html = renderToStaticMarkup(createElement(Streamdown, {mode: "static", plugins}, content));
    assert.match(html, /class="katex"/);
    assert.match(html, /<mfrac>/);
    assert.doesNotMatch(html, /katex-error/);
    if (display) {
      assert.match(html, /class="katex-display"/);
      assert.match(html, /display="block"/);
    } else {
      assert.match(html, /中文说明/);
      assert.doesNotMatch(html, /class="katex-display"/);
    }
  }
});
