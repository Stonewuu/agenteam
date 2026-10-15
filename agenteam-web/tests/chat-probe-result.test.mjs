import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/chat/components/chat-probe-dialog.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX}}).outputText;

function checkAction(status, label) {
  const exports = {};
  const calls = [];
  let pending;
  let refreshed = 0;
  let stateIndex = 0;
  const modules = {
    "react/jsx-runtime": {jsx: (type, props) => ({type, props}), jsxs: (type, props) => ({type, props})},
    react: {useState: (initial) => [stateIndex++ === 0 ? "group" : initial, () => {}]},
    "@/lib/i18n/locale-provider": {useT: () => text => text},
    "@/lib/http/use-api-query": {useApiPage: () => ({data: {items: []}})},
    "@/features/auth/hooks/use-form-action": {useFormAction: () => ({
      busy: false,
      mutation: {run: async (path, input) => {
        calls.push({path, input});
        return {status};
      }},
      execute: operation => {
        pending = operation();
        pending.catch(() => {});
      },
    })},
  };
  vm.runInNewContext(compiled, {exports, require: name => modules[name] ?? new Proxy({__esModule: true, default: {}}, {get: (target, key) => key in target ? target[key] : String(key)})});
  const tree = exports.ChatProbeDialog({path: "/chat", bot: {id: "bot", roleName: "岗位", revision: 7, providerCode: "feishu"}, onClose() {}, onChanged: () => refreshed++});
  function nodes(value) {
    if (!value || typeof value !== "object") {
      return [];
    }
    return [value, ...[value.props?.children].flat(Infinity).flatMap(nodes)];
  }
  const button = nodes(tree).find(node => node.type === "Button" && node.props.children === label);
  assert.equal(Boolean(button.props.disabled), false);
  button.props.onClick();
  return {pending, calls, refreshed: () => refreshed};
}

for (const [label, type] of [["检查机器人身份", "credential"], ["检查群历史读取", "readHistory"]]) {
  test(`${label}的业务失败不会作为成功返回`, async () => {
    for (const status of ["failed", "unavailable", undefined]) {
      const result = checkAction(status, label);
      await assert.rejects(result.pending, /检查.*重试/);
      assert.equal(result.calls[0].input.body.type, type);
      assert.equal(result.refreshed(), 1);
    }
  });
  test(`${label}只有明确通过才显示成功`, async () => {
    const result = checkAction("passed", label);
    await result.pending;
    assert.equal(result.refreshed(), 1);
    assert.equal(result.calls[0].input.body.expectedRevision, 7);
  });
}
