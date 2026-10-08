import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

function environment(reduced = false, surface = false) {
  const observers = [];
  const listeners = new Map();
  const animations = [];
  const media = { matches: reduced, addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: (name) => listeners.delete(name) };
  class Observer {
    constructor(callback) {
      this.callback = callback;
      observers.push(this);
    }
    observe() {}
    disconnect() {
      this.disconnected = true;
    }
  }
  const source = readFileSync(new URL("../src/components/ui/loading-size-transition.ts", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, ResizeObserver: Observer, MutationObserver: Observer, window: { matchMedia: () => media },
    getComputedStyle: () => ({ getPropertyValue: () => "ease-out" }),
  });
  const natural = { width: 300, height: 180 };
  let presented;
  let markers = [];
  function animate(frames, options) {
    const animation = { frames, options, effect: {}, cancel() {
      this.cancelled = true;
      presented = undefined;
    } };
    animations.push(animation);
    return animation;
  }
  const container = {
    dataset: {}, style: { width: "" }, querySelectorAll: () => markers, getClientRects: () => [{}],
    get offsetWidth() {
      return surface && animations.at(-1)?.effect === null ? natural.width : presented?.width ?? natural.width;
    },
    get offsetHeight() {
      return surface && animations.at(-1)?.effect === null ? natural.height : presented?.height ?? natural.height;
    },
    animate,
  };
  const content = surface ? container : { style: { width: "" }, get offsetWidth() {
    return natural.width;
  }, querySelectorAll: () => markers, animate };
  const controller = exports.observeLoadingSize(container, content, { includeNestedLoading: surface, fadeContent: !surface });
  return { container, content, controller, natural, animations, observers, listeners, media,
    present: (size) => {
      presented = size;
    },
    marker: (owner) => {
      markers = owner ? [{ closest: () => owner }] : [];
    },
  };
}

test("加载完成按实际高度收缩，首次渲染不播放尺寸动画", () => {
  const e = environment();
  e.controller.update("loading");
  assert.equal(e.animations.length, 0);
  e.natural.height = 68;
  e.controller.update("ready");
  const animation = e.animations[0];
  assert.equal(animation.frames[0].height, "180px");
  assert.equal(animation.frames[1].height, "68px");
  assert.equal(animation.options.duration, 220);
  animation.onfinish();
  assert.equal(e.container.dataset.sizeAnimating, undefined);
  assert.equal(e.container.offsetHeight, 68);
});

test("宽高同时过渡，结束后恢复自然宽度", () => {
  const e = environment();
  e.controller.update("loading");
  Object.assign(e.natural, { width: 450, height: 90 });
  e.controller.update("ready");
  assert.equal(e.animations[0].frames[0].width, "300px");
  assert.equal(e.animations[0].frames[1].width, "450px");
  assert.equal(e.content.style.width, "450px");
  e.animations[0].onfinish();
  assert.equal(e.content.style.width, "");
});

test("快速重新加载从当前尺寸接续，过期完成回调不会取消新动画", () => {
  const e = environment();
  e.controller.update("loading");
  e.natural.height = 70;
  e.controller.update("ready");
  e.present({ width: 300, height: 115 });
  e.natural.height = 220;
  e.controller.update("loading");
  assert.equal(e.animations[2].frames[0].height, "115px");
  assert.equal(e.animations[2].frames[1].height, "220px");
  e.animations[0].onfinish();
  assert.equal(e.animations[2].cancelled, undefined);
});

test("加载失败和重试继续进行尺寸过渡", () => {
  const e = environment();
  e.controller.update("loading");
  e.natural.height = 120;
  e.controller.update("error");
  e.animations[0].onfinish();
  e.natural.height = 180;
  e.controller.update("loading");
  assert.equal(e.animations[2].frames[0].height, "120px");
  assert.equal(e.animations[2].frames[1].height, "180px");
});

test("减少动效时不播放动画，运行中开启设置也会恢复自然尺寸", () => {
  const e = environment(true);
  e.controller.update("loading");
  e.natural.height = 60;
  e.controller.update("ready");
  assert.equal(e.animations.length, 0);
  e.media.matches = false;
  e.natural.height = 180;
  e.controller.update("loading");
  e.media.matches = true;
  e.listeners.get("change")();
  assert.equal(e.container.dataset.sizeAnimating, undefined);
  assert.equal(e.animations[0].cancelled, true);
});

test("就绪后的普通内容更新不触发整块尺寸动画", () => {
  const e = environment();
  e.controller.update("ready");
  e.natural.height = 260;
  e.observers[0].callback();
  e.controller.update("ready");
  assert.equal(e.animations.length, 0);
});

test("路由骨架子树替换时自动过渡，嵌套查询的骨架不重复触发父级", () => {
  const e = environment();
  e.marker(e.container);
  e.controller.update();
  e.natural.height = 80;
  e.marker({});
  e.observers[1].callback();
  assert.equal(e.animations[0].frames[1].height, "80px");
  e.animations[0].onfinish();
  e.marker(null);
  e.observers[1].callback();
  assert.equal(e.animations.length, 2);
});

test("卸载释放测量、媒体监听及运行中的动画", () => {
  const e = environment();
  e.controller.update("loading");
  e.natural.height = 80;
  e.controller.update("empty");
  e.controller.disconnect();
  assert.ok(e.observers.every((observer) => observer.disconnected));
  assert.equal(e.listeners.size, 0);
  assert.equal(e.animations.every((animation) => animation.cancelled), true);
});

test("弹窗将嵌套骨架与底部按钮一起纳入尺寸过渡，搜索框不淡出", () => {
  const e = environment(false, true);
  e.marker({});
  e.controller.update();
  e.marker(null);
  e.natural.height = 380;
  e.observers[1].callback();
  assert.equal(e.animations.length, 1);
  assert.equal(e.animations[0].frames[0].height, "180px");
  assert.equal(e.animations[0].frames[1].height, "380px");
  assert.equal(e.content.style.width, "");
});

test("弹窗多批内容在动画中继续返回时，从当前尺寸接续到最新高度", () => {
  const e = environment(false, true);
  e.marker({});
  e.controller.update();
  e.natural.height = 300;
  e.observers[1].callback();
  assert.equal(e.animations.length, 1);
  e.present({ width: 300, height: 220 });
  e.natural.height = 400;
  e.observers[1].callback();
  assert.equal(e.animations[1].frames[0].height, "220px");
  assert.equal(e.animations[1].frames[1].height, "400px");
});

test("弹层辅助节点更新但内容尺寸不变时，不重新启动动画", () => {
  const e = environment(false, true);
  e.marker({});
  e.controller.update();
  e.marker(null);
  e.natural.height = 100;
  e.observers[1].callback();
  e.present({ width: 300, height: 150 });
  e.observers[1].callback();
  assert.equal(e.animations.length, 1);
  assert.equal(e.animations[0].cancelled, undefined);
});
