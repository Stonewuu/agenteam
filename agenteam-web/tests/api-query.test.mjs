import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/lib/http/use-api-query.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

// 执行实际查询函数，控制请求返回顺序和等待时间，不访问业务接口。
function queryPage({polling = false, interval = 2500} = {}) {
  const states = [], effects = [], pendingEffects = [], requests = [];
  const timers = new Map();
  const listeners = new Map();
  const document = {visibilityState: "visible", addEventListener: (type, callback) => listeners.set(type, callback),
    removeEventListener: type => listeners.delete(type)};
  const navigator = {onLine: true};
  let stateIndex = 0, effectIndex = 0, timerId = 0, currentPath;
  const react = {
    useRef(initial) {
      const index = stateIndex++;
      if (!(index in states)) {
        states[index] = { current: initial };
      }
      return states[index];
    },
    useState(initial) {
      const index = stateIndex++;
      if (!(index in states)) {
        states[index] = typeof initial === "function" ? initial() : initial;
      }
      return [states[index], (next) => {
        states[index] = typeof next === "function" ? next(states[index]) : next;
      }];
    },
    useEffect(callback, dependencies) {
      const index = effectIndex++;
      const previous = effects[index];
      if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i]))) {
        pendingEffects.push(() => {
          previous?.cleanup?.();
          effects[index] = { dependencies, cleanup: callback() };
        });
      }
    },
  };
  const client = {
    ApiError: class extends Error {},
    errorMessage: (error) => error.message,
    apiRequest: (path, options) => new Promise((resolve, reject) => {
      requests.push({ path, ...options, resolve, reject });
    }),
  };
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, require: (name) => name === "react" ? react : client,
    AbortController, URLSearchParams, document, navigator,
    window: {
      addEventListener: document.addEventListener,
      removeEventListener: document.removeEventListener,
      setTimeout(callback) {
        timers.set(++timerId, callback);
        return timerId;
      },
      clearTimeout(id) {
        timers.delete(id);
      },
    },
  });
  function render(path = currentPath) {
    currentPath = path;
    stateIndex = 0;
    effectIndex = 0;
    const result = polling ? exports.useApiQuery(path, 0, 0, false, {refreshInterval: interval}) : exports.useApiPage(path);
    for (const effect of pendingEffects.splice(0)) {
      effect();
    }
    return result;
  }
  return {
    requests, render, document, navigator,
    accessError(status) {
      return Object.assign(new client.ApiError("无权读取"), {status});
    },
    notify(event) {
      listeners.get(event)?.();
    },
    unmount() {
      for (const effect of effects) {
        effect.cleanup?.();
      }
    },
    timers,
    startRequests() {
      const callbacks = [...timers.values()];
      timers.clear();
      for (const callback of callbacks) {
        callback();
      }
    },
    async resolve(index, data) {
      requests[index].resolve(data);
      await new Promise(resolve => setImmediate(resolve));
      return render();
    },
    async reject(index, error) {
      requests[index].reject(error);
      await new Promise(resolve => setImmediate(resolve));
      return render();
    },
  };
}

const mine = "/todos?scope=mine&status=open&query=";
const team = "/todos?scope=team&status=open&query=";
const firstPage = { items: [{ id: "mine-1", owner: "管理员" }], hasMore: true, nextCursor: "next-1" };

test("切换待办范围后，在新请求开始前就清空旧列表", async () => {
  const query = queryPage();
  query.render(mine);
  query.startRequests();
  assert.equal((await query.resolve(0, firstPage)).data, firstPage);
  const switched = query.render(team);
  assert.equal(switched.data, null);
  assert.equal(switched.loading, true);
  assert.equal(switched.previous.length, 0);
  query.startRequests();
  const loaded = await query.resolve(1, { items: [], hasMore: false, nextCursor: null });
  assert.equal(loaded.data.items.length, 0);
  assert.equal(loaded.loading, false);
});

test("团队、状态、搜索条件和企业变化都不能借用原列表", async () => {
  for (const changed of [team + "&teamId=another", team.replace("open", "completed"), team.replace("query=", "query=新的待办"), "/another-enterprise" + team]) {
    const query = queryPage();
    query.render(team);
    query.startRequests();
    await query.resolve(0, firstPage);
    assert.equal(query.render(changed).data, null);
  }
});

test("同一条件下翻页仍保留已有列表，切换范围会重置翻页位置", async () => {
  const query = queryPage();
  query.render(mine);
  query.startRequests();
  const initial = await query.resolve(0, firstPage);
  initial.next();
  const paging = query.render();
  assert.equal(paging.data, firstPage);
  assert.equal(paging.loading, true);
  assert.equal(paging.previous.length, 1);
  query.startRequests();
  assert.match(query.requests[1].path, /cursor=next-1/);
  const changed = query.render(team);
  assert.equal(changed.data, null);
  assert.equal(changed.previous.length, 0);
  query.startRequests();
  assert.doesNotMatch(query.requests[2].path, /cursor=/);
});

test("快速切换会取消旧请求，较晚返回的结果不能覆盖当前范围", async () => {
  const query = queryPage();
  query.render(mine);
  query.startRequests();
  query.render(team);
  assert.equal(query.requests[0].signal.aborted, true);
  query.startRequests();
  const teamPage = { items: [{ id: "team-1" }], hasMore: false, nextCursor: null };
  await query.resolve(1, teamPage);
  assert.equal((await query.resolve(0, firstPage)).data, teamPage);
});

test("新范围加载失败时显示本次错误，不恢复其他范围的数据", async () => {
  const query = queryPage();
  query.render(mine);
  query.startRequests();
  await query.resolve(0, firstPage);
  query.render(team);
  query.startRequests();
  const failed = await query.reject(1, new Error("暂时无法读取团队待办"));
  assert.equal(failed.data, null);
  assert.equal(failed.loading, false);
  assert.equal(failed.error, "暂时无法读取团队待办");
});

test("后台轮询保留现有数据，不触发前台加载提示，慢请求不会重叠", async () => {
  const query = queryPage({polling: true});
  assert.equal(query.render(mine).loading, true);
  query.startRequests();
  await query.resolve(0, firstPage);
  query.startRequests();
  const polling = query.render();
  assert.equal(polling.data, firstPage);
  assert.equal(polling.loading, false);
  assert.equal(polling.fetching, true);
  query.startRequests();
  query.notify("focus");
  assert.equal(query.requests.length, 2);
  assert.equal(query.requests[1].signal.aborted, false);
  const refreshed = await query.resolve(1, {...firstPage, hasMore: false});
  assert.equal(refreshed.fetching, false);
  assert.equal(query.timers.size, 1);
});

test("隐藏与断网时不轮询，恢复后立即读取，卸载后清除定时器和监听", async () => {
  const query = queryPage({polling: true});
  query.render(mine);
  query.startRequests();
  await query.resolve(0, firstPage);
  query.document.visibilityState = "hidden";
  query.startRequests();
  assert.equal(query.requests.length, 1);
  query.document.visibilityState = "visible";
  query.navigator.onLine = false;
  query.notify("visibilitychange");
  query.startRequests();
  assert.equal(query.requests.length, 1);
  query.navigator.onLine = true;
  query.notify("online");
  assert.equal(query.requests.length, 2);
  query.unmount();
  assert.equal(query.requests[1].signal.aborted, true);
  await query.resolve(1, {items: []});
  assert.equal(query.timers.size, 0);
  query.notify("focus");
  assert.equal(query.requests.length, 2);
});

test("后台失败保留已读内容和错误，失去访问权限后清空内容并停止轮询", async () => {
  const query = queryPage({polling: true});
  query.render(mine);
  query.startRequests();
  await query.resolve(0, firstPage);
  query.startRequests();
  const unavailable = await query.reject(1, new Error("网络暂时不可用"));
  assert.equal(unavailable.data, firstPage);
  assert.equal(unavailable.loading, false);
  assert.equal(unavailable.error, "网络暂时不可用");
  query.startRequests();
  const rejected = await query.reject(2, query.accessError(403));
  assert.equal(rejected.data, null);
  assert.equal(query.timers.size, 0);
  query.notify("focus");
  assert.equal(query.requests.length, 3);
});

test("后台任务结束后停止定时轮询，切回页面仍可核对状态", async () => {
  const query = queryPage({polling: true, interval: data => data?.complete ? 0 : 2500});
  query.render(mine);
  query.startRequests();
  await query.resolve(0, {complete: false});
  assert.equal(query.timers.size, 1);
  query.startRequests();
  await query.resolve(1, {complete: true});
  assert.equal(query.timers.size, 0);
  query.notify("focus");
  assert.equal(query.requests.length, 3);
  assert.equal(query.render().loading, false);
});
