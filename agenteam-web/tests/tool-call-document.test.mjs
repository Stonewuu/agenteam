import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import ts from "typescript";

const compile = path => ts.transpileModule(readFileSync(new URL(path, import.meta.url), "utf8"), {
  compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX},
}).outputText;
const formatting = {};
vm.runInNewContext(compile("../src/features/plugin/lib/tool-payload-format.ts"), {exports: formatting});
const compiled = compile("../src/features/plugin/components/tool-call-details.tsx");
const location = {url: "/tool-content", part: "result", revision: "completed"};

// 执行生产组件的读取逻辑，控制接口完成顺序，检查已有内容是否触发网络读取。
function documentView() {
  const states = [], effects = [], memos = [], pending = [], requests = [];
  let stateIndex, effectIndex, memoIndex;
  const changed = (before, after) => !before || after.some((value, index) => !Object.is(value, before[index]));
  const react = {
    useState(initial) {
      const index = stateIndex++;
      if (!(index in states)) {
        states[index] = initial;
      }
      return [states[index], value => {
        states[index] = typeof value === "function" ? value(states[index]) : value;
      }];
    },
    useMemo(create, dependencies) {
      const index = memoIndex++;
      if (changed(memos[index]?.dependencies, dependencies)) {
        memos[index] = {dependencies, value: create()};
      }
      return memos[index].value;
    },
    useEffect(callback, dependencies) {
      const index = effectIndex++;
      if (changed(effects[index]?.dependencies, dependencies)) {
        pending.push(() => {
          effects[index]?.cleanup?.();
          effects[index] = {dependencies, cleanup: callback()};
        });
      }
    },
  };
  const exports = {};
  vm.runInNewContext(compiled + "\nexports.readDocument = useToolCallDocument;", {
    exports, AbortController,
    require: name => name === "react" ? react : name.endsWith("tool-payload-format") ? formatting
      : name.endsWith("api/tool-content") ? {readToolContentPreview: (source, raw, signal) => new Promise(resolve => {
        requests.push({source, raw, signal, resolve});
      })} : {},
  });
  return {
    requests,
    render(value, raw = false, source = location) {
      stateIndex = effectIndex = memoIndex = 0;
      const result = exports.readDocument(value, source, raw);
      for (const effect of pending.splice(0)) {
        effect();
      }
      return result;
    },
  };
}

test("完整结果在易读和原始模式都直接使用，复制不带详情地址", () => {
  const view = documentView();
  const full = '{"content":[{"type":"text","text":"已保存"}],"isError":false}';
  for (const raw of [false, true, false]) {
    const result = view.render(full, raw);
    assert.equal(result.value, full);
    assert.equal(result.loading, false);
    assert.equal(result.source, undefined);
  }
  assert.equal(view.requests.length, 0);
});

test("截断预览读取详情，同一内容重绘不重复请求，返回完整内容后可显示", async () => {
  const view = documentView();
  const preview = '{"truncated":true,"content":"首段"}';
  assert.equal(view.render(preview).loading, true);
  view.render(preview);
  assert.equal(view.requests.length, 1);
  assert.equal(view.requests[0].raw, false);
  view.requests[0].resolve({content: "完整结果", eof: true});
  await new Promise(resolve => setImmediate(resolve));
  const result = view.render(preview);
  assert.equal(result.value, "完整结果");
  assert.equal(result.loading, false);
  assert.equal(result.document.eof, true);
});

test("完整结果到达后取消预览请求，晚到的旧结果不能覆盖它", async () => {
  const view = documentView();
  view.render('{"truncated":true,"content":"旧预览"}');
  const current = view.render("当前完整结果");
  assert.equal(current.value, "当前完整结果");
  assert.equal(view.requests[0].signal.aborted, true);
  view.requests[0].resolve({content: "旧结果", eof: true});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(view.render("当前完整结果").value, "当前完整结果");
  assert.equal(view.requests.length, 1);
});
