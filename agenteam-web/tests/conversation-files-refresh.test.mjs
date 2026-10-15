import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

const compiled = ts.transpileModule(readFileSync(new URL("../src/features/file/hooks/use-conversation-files.ts", import.meta.url), "utf8"), {
  compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022},
}).outputText;

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

// 控制实际文件查询函数的请求完成顺序，覆盖轮询、翻页和切换会话之间的竞争。
function fileQuery() {
  const slots = [], effects = [], pending = [], requests = [];
  let slot = 0, effect = 0;
  let options = {enterprise: "enterprise", conversation: "first", scope: "context", path: "", active: true, refresh: "0"};
  const react = {
    useState(initial) {
      const index = slot++;
      if (!(index in slots)) {
        slots[index] = typeof initial === "function" ? initial() : initial;
      }
      return [slots[index], (value) => {
        slots[index] = typeof value === "function" ? value(slots[index]) : value;
      }];
    },
    useRef(initial) {
      const index = slot++;
      slots[index] ??= {current: initial};
      return slots[index];
    },
    useCallback(callback, dependencies) {
      const index = slot++;
      if (!slots[index] || dependencies.some((value, i) => !Object.is(value, slots[index].dependencies[i]))) {
        slots[index] = {callback, dependencies};
      }
      return slots[index].callback;
    },
    useEffect(callback, dependencies) {
      const index = effect++;
      const previous = effects[index];
      if (!previous || dependencies.some((value, i) => !Object.is(value, previous.dependencies[i]))) {
        pending.push(() => {
          previous?.cleanup?.();
          effects[index] = {dependencies, cleanup: callback()};
        });
      }
    },
  };
  const exports = {};
  vm.runInNewContext(compiled, {
    exports, AbortController, console: {error() {}},
    require(name) {
      if (name === "react") {
        return react;
      }
      if (name.includes("api-client")) {
        return {ApiError, errorMessage: (failure) => failure.message};
      }
      return {listConversationFiles: (enterprise, conversation, scope, path, cursor, signal) => new Promise((resolve, reject) => {
        requests.push({enterprise, conversation, scope, path, cursor, signal, resolve, reject});
      })};
    },
  });
  function render(next = {}) {
    options = {...options, ...next};
    slot = 0;
    effect = 0;
    const value = exports.useConversationFiles(options.enterprise, options.conversation, options.scope, options.path, options.active, false, options.refresh);
    for (const callback of pending.splice(0)) {
      callback();
    }
    return value;
  }
  return {
    requests, render,
    async resolve(index, items, nextCursor = null) {
      requests[index].resolve({items, nextCursor, hasMore: Boolean(nextCursor)});
      await new Promise(setImmediate);
      return render();
    },
    async reject(index, failure) {
      requests[index].reject(failure);
      await new Promise(setImmediate);
      return render();
    },
  };
}

const file = {id: "f.one", name: "方案.md", path: "outputs/方案.md", directory: false, source: "generated", mediaType: "text/markdown", sizeBytes: 100, modifiedAt: "2026-10-15T01:00:00Z", revision: "1", previewKind: "markdown"};

test("已经确认的空列表在后台刷新时不会重新进入首次加载", async () => {
  const query = fileQuery();
  assert.equal(query.render().initialLoading, true);
  const loaded = await query.resolve(0, []);
  assert.equal(loaded.initialLoading, false);
  query.render({refresh: "1"});
  const refreshing = query.render();
  assert.equal(refreshing.loading, true);
  assert.equal(refreshing.initialLoading, false);
  assert.equal(refreshing.items, loaded.items);
  assert.equal((await query.resolve(1, [])).items, loaded.items);
});

test("已有文件在刷新期间保留，无变化时沿用列表，变化后显示最新元数据", async () => {
  const query = fileQuery();
  query.render();
  const loaded = await query.resolve(0, [file]);
  query.render({refresh: "1"});
  assert.equal(query.render().items, loaded.items);
  assert.equal((await query.resolve(1, [{...file}])).items, loaded.items);
  query.render({refresh: "2"});
  assert.equal((await query.resolve(2, [{...file, revision: "2", sizeBytes: 200}])).items[0].sizeBytes, 200);
  query.render({refresh: "3"});
  assert.equal((await query.resolve(3, [])).items.length, 0);
});

test("刷新已经展开的两页时保留内容和后续游标，全部成功后一起替换", async () => {
  const query = fileQuery();
  query.render();
  const first = await query.resolve(0, [file], "page-2");
  const loadingMore = first.loadMore();
  const expanded = await query.resolve(1, [{...file, id: "f.two"}], "page-3");
  await loadingMore;
  query.render({refresh: "1"});
  assert.equal(query.render().items.length, 2);
  assert.equal(query.render().hasMore, true);
  const during = await query.resolve(2, [{...file, sizeBytes: 300}], "new-page-2");
  assert.equal(during.items, expanded.items);
  assert.equal(query.requests[3].cursor, "new-page-2");
  const refreshed = await query.resolve(3, [{...file, id: "f.two", sizeBytes: 400}], "new-page-3");
  assert.equal(refreshed.items[0].sizeBytes, 300);
  assert.equal(refreshed.items[1].sizeBytes, 400);
  assert.equal(refreshed.hasMore, true);
});

test("暂时失败保留文件及翻页能力，重试期间保留错误，成功后清除", async () => {
  const query = fileQuery();
  query.render();
  const loaded = await query.resolve(0, [file], "page-2");
  query.render({refresh: "1"});
  const failed = await query.reject(1, new Error("暂时无法读取文件"));
  assert.equal(failed.items, loaded.items);
  assert.equal(failed.hasMore, true);
  failed.reload();
  query.render();
  assert.equal(query.render().error, "暂时无法读取文件");
  assert.equal((await query.resolve(2, [file], "page-2")).error, "");
});

test("登录、访问权限或会话失效后不继续展示旧文件", async () => {
  for (const status of [401, 403, 404]) {
    const query = fileQuery();
    query.render();
    await query.resolve(0, [file], "page-2");
    query.render({refresh: "1"});
    const failed = await query.reject(1, new ApiError(status, "无法读取此对话"));
    assert.equal(failed.items.length, 0);
    assert.equal(failed.hasMore, false);
    assert.equal(failed.initialLoading, false);
    assert.equal(failed.error, "无法读取此对话");
  }
});

test("切换会话立即清空旧文件，晚到的旧响应不能覆盖新列表", async () => {
  const query = fileQuery();
  query.render();
  await query.resolve(0, [file]);
  query.render({refresh: "1"});
  const switched = query.render({conversation: "second"});
  assert.equal(switched.items.length, 0);
  assert.equal(switched.initialLoading, true);
  assert.equal(query.requests[1].signal.aborted, true);
  await query.resolve(2, [{...file, id: "f.second"}]);
  assert.equal((await query.resolve(1, [file])).items[0].id, "f.second");
});

test("刷新取消未完成的加载更多后，按钮不再停留在加载状态", async () => {
  const query = fileQuery();
  query.render();
  const first = await query.resolve(0, [file], "page-2");
  const loadingMore = first.loadMore();
  assert.equal(query.render().loadingMore, true);
  query.render({refresh: "1"});
  assert.equal(query.requests[1].signal.aborted, true);
  assert.equal(query.render().loadingMore, false);
  await query.resolve(2, [file], "page-2");
  await query.resolve(1, [{...file, id: "f.late"}]);
  await loadingMore;
  assert.equal(query.render().items.length, 1);
});
