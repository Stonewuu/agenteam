import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

function settings(providerCode, status = "active") {
  const calls = [];
  let pending;
  const binding = {id: "binding", connectionId: "connection", connectionName: "企业账号", providerName: providerCode,
    providerCode, status, revision: "3", receiveEnabled: true, chatEnabled: true, externalLoginEnabled: false,
    bindingAvailable: status === "active", loginAvailable: status === "active", messagingAvailable: status === "active"};
  const action = {busy: false, fieldErrors: {}, mutation: {async run(path, request) {
    calls.push({path, ...request});
  }}, execute(operation) {
    pending = operation();
    return pending;
  }};
  const modules = {
    react: {useState: value => [value, () => {}], useCallback: value => value, useRef: value => ({current: value})},
    "react/jsx-runtime": {jsx: (type, props) => ({type, props}), jsxs: (type, props) => ({type, props}), Fragment: "Fragment"},
    "@/lib/i18n/locale-provider": {useT: () => value => value},
    "@/features/auth/hooks/use-form-action": {useFormAction: () => action},
    "@/lib/http/use-api-query": {useApiQuery: () => ({data: [binding], loading: false})},
    "../types/channel-binding": {channelsPath: enterprise => `/api/v1/enterprises/${enterprise}/me/channels`},
  };
  const exports = {};
  const source = readFileSync(new URL("../src/features/integration/components/channel-settings-panel.tsx", import.meta.url), "utf8");
  const code = ts.transpileModule(source, {compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX,
  }}).outputText;
  vm.runInNewContext(code, {exports, require: name => modules[name] ?? new Proxy({}, {
    get: (_, key) => key === "default" ? new Proxy({}, {get: (_, style) => String(style)}) : String(key),
  })});
  function nodes(value) {
    if (Array.isArray(value)) {
      return value.flatMap(nodes);
    }
    if (!value || typeof value !== "object") {
      return [];
    }
    return [value, ...nodes(value.props?.children)];
  }
  const tree = nodes(exports.ChannelSettingsPanel({enterpriseId: "enterprise", superAdmin: true}));
  return {tree, calls, finished: () => pending};
}

for (const provider of ["feishu", "wecom"]) {
  test(`超级管理员可以查看并开启${provider}绑定的登录选项`, async () => {
    const view = settings(provider);
    const login = view.tree.find(node => node.type === "SettingRow" && node.props.label === "允许使用此账号登录");
    assert.ok(login);
    assert.equal(login.props.disabled, false);
    login.props.onChange(true);
    await view.finished();
    assert.equal(view.calls.length, 1);
    assert.equal(view.calls[0].path, "/api/v1/enterprises/enterprise/me/channels/binding");
    assert.equal(view.calls[0].revision, "3");
    assert.equal(view.calls[0].body.externalLoginEnabled, true);
    assert.equal(view.calls[0].body.chatEnabled, true);
  });
}

test("停用绑定不能显示可聊天的成功提示，仍保留解除绑定入口", () => {
  const view = settings("feishu", "disabled");
  assert.equal(view.tree.some(node => node.type === "Guidance" && node.props.tone === "success"), false);
  const status = view.tree.find(node => node.type === "DetailStatus");
  assert.equal(status.props.tone, "neutral");
  assert.equal(status.props.children, "绑定已停用");
  assert.ok(view.tree.filter(node => node.type === "SettingRow").every(node => node.props.disabled));
  assert.ok(view.tree.some(node => node.type === "Button" && node.props.children?.includes("解除绑定") && !node.props.disabled));
});
