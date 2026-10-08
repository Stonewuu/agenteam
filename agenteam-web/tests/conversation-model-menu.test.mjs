import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

function compile(path, modules) {
  const source = readFileSync(new URL(path, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: (name) => modules[name], console: modules.console ?? console });
  return exports;
}

const reasoning = compile("../src/features/modelprofile/lib/reasoning-effort.ts", {});
const models = [
  { id: "first", name: "当前模型", available: true, reasoningEfforts: ["high"] },
  { id: "second", name: "另一个模型", available: true, reasoningEfforts: ["none", "low", "high"] },
  { id: "plain", name: "普通模型", available: true, reasoningEfforts: [] },
  { id: "disabled", name: "停用模型", available: false, reasoningEfforts: ["high"], unavailableReason: "已停用" },
];

function modelHook(options = {}) {
  const slots = [], calls = [], logs = [];
  let cursor = 0;
  const original = { id: "conversation", revision: "8", mode: "normal", status: "active", activeRunId: options.running ? "run" : null,
    modelSelection: { modelProfileId: "first", reasoningEffort: "high" } };
  const data = { configurable: true, models, selection: original.modelSelection, defaultSelection: original.modelSelection };
  const query = { data, loading: false, retry: () => calls.push({ retry: true }) };
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) {
        slots[index] = initial;
      }
      return [slots[index], (value) => {
        slots[index] = typeof value === "function" ? value(slots[index]) : value;
      }];
    },
    useRef(value) {
      const index = cursor++;
      return slots[index] ??= { current: value };
    },
  };
  class ApiMutation {
    async run(path, request) {
      calls.push({ path, ...request });
      if (options.fail) {
        throw options.fail;
      }
      return { ...original, revision: "9", modelSelection: request.body };
    }
  }
  const { useConversationModel: readModel } = compile("../src/features/agent/hooks/use-conversation-model.ts", {
    react, console: { error: (...args) => logs.push(args) },
    "@/lib/http/api-client": { ApiError: Error, ApiMutation, errorMessage: (error) => error.message },
    "@/lib/http/use-api-query": { useApiQuery: () => query },
    "@/features/enterprise/api/organization-api": { organizationPath: () => "/models" },
    "@/features/modelprofile/lib/reasoning-effort": reasoning,
    "../api/conversation-api": { conversationPath: () => "/conversations/conversation" },
  });
  return { calls, logs, render() {
    cursor = 0;
    return readModel({ enterprise: "enterprise", agentId: "employee", draftKey: "draft", enabled: true, busy: false,
      conversation: options.draft ? undefined : original });
  } };
}

test("从另一模型的二级菜单选择等级时，只提交一次完整选择并带上当前版本", async () => {
  const hook = modelHook();
  hook.render().changeModel("second", "low");
  await new Promise(setImmediate);
  assert.equal(hook.calls.length, 1);
  assert.equal(hook.calls[0].revision, "8");
  assert.equal(hook.calls[0].method, "PUT");
  assert.equal(hook.calls[0].body.modelProfileId, "second");
  assert.equal(hook.calls[0].body.reasoningEffort, "low");
  assert.equal(hook.render().selection.reasoningEffort, "low");
  assert.equal(hook.render().selection.modelProfileId, "second");
});

test("模型默认、不思考和不支持思考的模型分别保存正确等级", async () => {
  for (const [id, effort] of [["second", null], ["second", "none"], ["plain", null]]) {
    const hook = modelHook();
    hook.render().changeModel(id, effort);
    await new Promise(setImmediate);
    assert.equal(hook.calls[0].body.reasoningEffort, effort);
    assert.equal(hook.render().selection.modelProfileId, id);
  }
});

test("不可用模型、不支持的等级和执行中的对话不会发出保存请求", () => {
  const hook = modelHook();
  hook.render().changeModel("disabled", "high");
  hook.render().changeModel("second", "max");
  hook.render().changeModel("plain", "high");
  assert.equal(hook.calls.length, 0);
  const running = modelHook({ running: true });
  running.render().changeModel("second", "low");
  assert.equal(running.calls.length, 0);
});

test("连续选择时等待当前保存完成，失败保留原模型并显示原因", async () => {
  const failure = new Error("保存失败，请重试");
  const hook = modelHook({ fail: failure });
  const state = hook.render();
  state.changeModel("second", "low");
  state.changeModel("plain", null);
  await new Promise(setImmediate);
  assert.equal(hook.calls.filter((call) => call.method).length, 1);
  assert.equal(hook.render().selection.modelProfileId, "first");
  assert.equal(hook.render().error, failure.message);
  assert.equal(hook.logs[0].at(-1), failure);
  assert.equal(hook.calls.at(-1).retry, true);
});

test("新任务的二级选择保存在草稿中，发送前不会写入已有对话", () => {
  const hook = modelHook({ draft: true });
  hook.render().changeModel("second", "low");
  assert.equal(hook.render().selection.modelProfileId, "second");
  assert.equal(hook.render().selection.reasoningEffort, "low");
  assert.equal(hook.calls.length, 0);
});
