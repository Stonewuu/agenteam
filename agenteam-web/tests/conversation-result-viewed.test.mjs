import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/agent/hooks/use-conversation-result-viewed.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
const run = { id: "run-1", conversationId: "conversation-1", status: "completed", mode: "interactive" };

function page(options = {}) {
  const document = new EventTarget();
  const window = new EventTarget();
  const timers = new Map();
  const requests = [];
  const errors = [];
  let sequence = 0;
  let cleanup;
  let changed = 0;
  document.visibilityState = options.hidden ? "hidden" : "visible";
  document.hasFocus = () => options.focused !== false;
  window.setTimeout = (callback, delay) => {
    const id = ++sequence;
    timers.set(id, { callback, delay });
    return id;
  };
  window.clearTimeout = (id) => timers.delete(id);
  class ApiError extends Error {
    constructor(status) {
      super("确认失败");
      this.status = status;
    }
  }
  const modules = {
    react: { useEffect: (callback) => {
      cleanup = callback();
    } },
    "@/lib/http/api-client": {
      ApiError,
      apiRequest: (path, value) => {
        requests.push({ path, ...value });
        return options.request?.(requests.length, value.signal) ?? Promise.resolve();
      },
      reportRequestFailure: (error, method, path) => errors.push({ error, method, path }),
    },
    "@/features/notification/components/notification-center-provider": { notificationsChanged: () => {
      changed += 1;
    } },
    "../api/conversation-api": { runPath: (enterprise, id) => `/api/v1/enterprises/${enterprise}/runs/${id}` },
  };
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: (name) => modules[name], AbortController, window, document });
  exports.useConversationResultViewed("enterprise-1", options.conversation ?? "conversation-1", options.run ?? run, options.ready !== false);
  return {
    requests, errors, ApiError, document, window, timers,
    changed: () => changed,
    cleanup: () => cleanup?.(),
    async tick() {
      const pending = [...timers.entries()];
      for (const [id, value] of pending) {
        timers.delete(id);
        value.callback();
      }
      await new Promise((resolve) => setImmediate(resolve));
    },
  };
}

test("前台当前对话收到成功结果后只确认一次，并刷新通知数量", async () => {
  const value = page();
  assert.equal(value.requests.length, 0);
  await value.tick();
  assert.equal(value.requests.length, 1);
  assert.equal(value.requests[0].path, "/api/v1/enterprises/enterprise-1/runs/run-1/result-viewed");
  assert.equal(value.requests[0].method, "POST");
  assert.equal(value.changed(), 1);
  value.window.dispatchEvent(new Event("focus"));
  value.document.dispatchEvent(new Event("visibilitychange"));
  await value.tick();
  assert.equal(value.requests.length, 1);
  value.cleanup();
});

test("后台标签页不确认，返回并聚焦后才确认", async () => {
  const value = page({ hidden: true });
  await value.tick();
  assert.equal(value.requests.length, 0);
  value.document.visibilityState = "visible";
  value.document.dispatchEvent(new Event("visibilitychange"));
  await value.tick();
  assert.equal(value.requests.length, 1);
  value.cleanup();
});

test("可见但没有焦点的窗口不确认", async () => {
  const options = { focused: false };
  const value = page(options);
  await value.tick();
  assert.equal(value.requests.length, 0);
  options.focused = true;
  value.window.dispatchEvent(new Event("focus"));
  await value.tick();
  assert.equal(value.requests.length, 1);
  value.cleanup();
});

test("运行中、失败、预览、其他对话和未加载完成的页面均不确认", async () => {
  for (const options of [
    { run: { ...run, status: "running" } },
    { run: { ...run, status: "failed" } },
    { run: { ...run, mode: "preview" } },
    { conversation: "conversation-2" },
    { ready: false },
  ]) {
    const value = page(options);
    await value.tick();
    assert.equal(value.requests.length, 0);
    value.cleanup();
  }
});

test("切换对话会清除待发送确认，取消请求后也不再重试", async () => {
  const before = page();
  before.cleanup();
  await before.tick();
  assert.equal(before.requests.length, 0);
  const during = page({ request: (_count, signal) => new Promise((_resolve, reject) => {
    signal.addEventListener("abort", () => reject(new Error("页面已离开")), { once: true });
  }) });
  await during.tick();
  during.cleanup();
  await during.tick();
  assert.equal(during.requests[0].signal.aborted, true);
  assert.equal(during.timers.size, 0);
  assert.equal(during.errors.length, 0);
});

test("暂时失败会重试，但重试时退到后台不会发送", async () => {
  const value = page({ request: (count) => count === 1 ? Promise.reject(new Error("网络暂不可用")) : Promise.resolve() });
  await value.tick();
  assert.equal(value.errors.length, 1);
  value.document.visibilityState = "hidden";
  await value.tick();
  assert.equal(value.requests.length, 1);
  value.document.visibilityState = "visible";
  value.window.dispatchEvent(new Event("online"));
  await value.tick();
  assert.equal(value.requests.length, 2);
  assert.equal(value.changed(), 1);
  value.cleanup();
});

test("请求尚未结束时，多次聚焦不会并发确认", async () => {
  let resolve;
  const value = page({ request: () => new Promise((done) => {
    resolve = done;
  }) });
  await value.tick();
  value.window.dispatchEvent(new Event("focus"));
  value.window.dispatchEvent(new Event("online"));
  assert.equal(value.requests.length, 1);
  resolve();
  await value.tick();
  assert.equal(value.changed(), 1);
  value.cleanup();
});

test("持续失败时逐步降低重试频率，连接恢复后仍会确认", async () => {
  const value = page({ request: (count) => count <= 7 ? Promise.reject(new Error("服务暂不可用")) : Promise.resolve() });
  const delays = [];
  for (let count = 0; count < 7; count += 1) {
    await value.tick();
    delays.push([...value.timers.values()][0].delay);
  }
  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  await value.tick();
  assert.equal(value.requests.length, 8);
  assert.equal(value.changed(), 1);
  assert.equal(value.timers.size, 0);
  value.cleanup();
});
