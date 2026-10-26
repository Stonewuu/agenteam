import assert from "node:assert/strict";
import { test } from "node:test";
import { loadSource } from "./i18n-test-runtime.mjs";

const { createRefreshFeedbackStore } = loadSource("components/ui/refresh-feedback-store");

function setup() {
  let now = 0,
    next = 0;
  const timers = new Map();
  const store = createRefreshFeedbackStore({
    now: () => now,
    after: (callback, delay) => {
      timers.set(++next, { callback, at: now + delay });
      return next;
    },
    cancel: (id) => timers.delete(id),
  });
  return {
    store,
    advance(ms) {
      const until = now + ms;
      while (true) {
        const first = [...timers.entries()].sort((a, b) => a[1].at - b[1].at)[0];
        if (!first || first[1].at > until) {
          break;
        }
        now = first[1].at;
        timers.delete(first[0]);
        first[1].callback();
      }
      now = until;
    },
  };
}

test("短请求结束或组件卸载后不会闪现刷新提示", () => {
  const { store, advance } = setup();
  const stop = store.begin();
  advance(249);
  assert.equal(store.getSnapshot().phase, "hidden");
  stop();
  stop();
  advance(2000);
  assert.deepEqual(store.getSnapshot(), { phase: "hidden", busy: false });
});

test("持续刷新延迟出现，结束后保留退出动画，不伪造成功状态", () => {
  const { store, advance } = setup();
  const stop = store.begin();
  advance(250);
  assert.deepEqual(store.getSnapshot(), { phase: "visible", busy: true });
  stop();
  advance(399);
  assert.deepEqual(store.getSnapshot(), { phase: "visible", busy: false });
  advance(1);
  assert.equal(store.getSnapshot().phase, "leaving");
  advance(179);
  assert.equal(store.getSnapshot().phase, "leaving");
  advance(1);
  assert.deepEqual(store.getSnapshot(), { phase: "hidden", busy: false });
});

test("并发刷新合并为一份反馈，新增请求不延后首次显示", () => {
  const { store, advance } = setup();
  const first = store.begin();
  advance(200);
  const second = store.begin();
  advance(50);
  assert.equal(store.getSnapshot().phase, "visible");
  first();
  advance(1000);
  assert.equal(store.getSnapshot().busy, true);
  second();
  advance(180);
  assert.equal(store.getSnapshot().phase, "hidden");
});

test("淡出过程中继续刷新时恢复原提示，旧计时器不能将它关闭", () => {
  const { store, advance } = setup();
  const first = store.begin();
  advance(900);
  first();
  advance(100);
  assert.equal(store.getSnapshot().phase, "leaving");
  const second = store.begin();
  assert.deepEqual(store.getSnapshot(), { phase: "visible", busy: true });
  advance(1000);
  assert.deepEqual(store.getSnapshot(), { phase: "visible", busy: true });
  second();
  advance(180);
  assert.equal(store.getSnapshot().phase, "hidden");
});

test("订阅清理及开发模式重复挂载不会留下刷新来源", () => {
  const { store, advance } = setup();
  let changes = 0;
  const unsubscribe = store.subscribe(() => changes++);
  const abandoned = store.begin();
  abandoned();
  const active = store.begin();
  advance(250);
  assert.equal(store.getSnapshot().phase, "visible");
  unsubscribe();
  const previous = changes;
  active();
  advance(1000);
  assert.equal(changes, previous);
  assert.deepEqual(store.getServerSnapshot(), { phase: "hidden", busy: false });
  assert.deepEqual(store.getSnapshot(), { phase: "hidden", busy: false });
});
