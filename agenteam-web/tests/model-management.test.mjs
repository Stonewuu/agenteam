import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {chineseText, loadSource, localizeCatalog} from "./i18n-test-runtime.mjs";

const protocol = loadSource("features/modelprofile/lib/model-protocol");
const provider = {id: "provider", name: "已使用的连接", revision: "3", protocol: "openai", baseUrl: "https://example.com/v1",
  keyConfigured: true, enabled: true, modelCount: 1, inUse: true};
const model = {id: "model", name: "已使用的模型", revision: "4", providerId: provider.id, providerName: provider.name,
  providerEnabled: true, modelName: "original-model", enabled: false, inUse: true,
  capabilities: {supportsTools: true, supportsTemperature: true, maxContextTokens: 128000, maxOutputTokens: 8192,
    inputTypes: ["text", "image"], reasoningEfforts: ["high"], protocolOptions: protocol.defaultProtocolOptions("openai")}};

// 使用真实表单和菜单事件，界面外观及网络入口替换为轻量对象。
function component(file, exported, props, busy = false) {
  const slots = [], calls = [];
  let cursor = 0, pending;
  const action = {busy, fieldErrors: {}, clearFieldError() {}, resetFeedback() {}, mutation: {
    async run(path, request) {
      calls.push({path, ...request});
    },
  }, execute(operation) {
    pending = operation();
    return pending;
  }};
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) {
        slots[index] = typeof initial === "function" ? initial() : initial;
      }
      return [slots[index], (value) => {
        slots[index] = typeof value === "function" ? value(slots[index]) : value;
      }];
    },
    useId() {
      return `field-${cursor++}`;
    },
  };
  const modules = {
    react,
    "react/jsx-runtime": {jsx: (type, props) => ({type, props}), jsxs: (type, props) => ({type, props}), Fragment: "Fragment"},
    "@/lib/i18n/locale-provider": {useT: () => chineseText},
    "@/lib/i18n/translate": {localizeCatalog},
    "@/features/auth/hooks/use-form-action": {useFormAction: () => action},
    "@/features/workspace/components/use-confirm-close": {useConfirmClose: () => ({finish: (callback) => callback(), dialogRef: {}})},
    "@/features/enterprise/api/organization-api": {organizationPath: (enterprise, path) => `/enterprises/${enterprise}${path}`},
    "@/lib/http/use-api-query": {useApiQuery: (path) => ({data: path.endsWith("/model-providers") ? [provider] : [model], loading: false})},
    "@/components/ui/dialog": {Dialog: "Dialog", DialogCancel: "DialogCancel", DialogActions: "DialogActions",
      useDialogControl: () => ({ref: {}, close: (callback) => callback()})},
    "../lib/model-protocol": protocol,
    "../lib/model-token-length": loadSource("features/modelprofile/lib/model-token-length"),
    "../lib/reasoning-effort": loadSource("features/modelprofile/lib/reasoning-effort"),
  };
  const exports = {};
  const source = readFileSync(new URL(`../src/features/modelprofile/components/${file}.tsx`, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, {compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX,
  }}).outputText;
  vm.runInNewContext(compiled, {exports, require: (name) => modules[name] ?? new Proxy({}, {
    get: (_, key) => key === "default" ? new Proxy({}, {get: (_, style) => String(style)}) : String(key),
  })});
  const render = () => {
    cursor = 0;
    return exports[exported](props);
  };
  function nodes(node) {
    if (Array.isArray(node)) {
      return node.flatMap((item) => nodes(item));
    }
    if (!node || typeof node !== "object") {
      return [];
    }
    return [node, ...nodes(node.props?.children ?? [])];
  }
  const find = (type, predicate = () => true) => nodes(render()).find((node) => node.type === type && predicate(node.props));
  return {calls, action, render, nodes: (node = render()) => nodes(node), find, finished: () => pending};
}

test("已使用模型可以更换连接、型号、长度及能力，并带当前版本保存", async () => {
  const form = component("model-profile-dialog", "ModelProfileDialog", {enterpriseId: "enterprise", model,
    providers: [provider, {...provider, id: "replacement"}], preferredProvider: provider.id, onClose() {}, onSaved() {}});
  assert.ok(form.nodes().filter((node) => node.type === "Fieldset").every((node) => !node.props.disabled));
  form.find("Select", (props) => props.name === "providerId").props.onChange({target: {value: "replacement"}});
  form.find("RemoteModelPicker").props.onChange("changed-model");
  form.find("TokenLengthInput", (props) => props.name === "capabilities.maxContextTokens").props.onChange("32K");
  form.find("TokenLengthInput", (props) => props.name === "capabilities.maxOutputTokens").props.onChange("4K");
  form.find("Toggle", (props) => props.label === "支持工具调用").props.onChange(false);
  form.find("Toggle", (props) => props.label === "支持调整生成温度").props.onChange(false);
  form.find("ModelProtocolFields").props.onChange({...protocol.defaultProtocolOptions("openai"), serviceTier: "priority"});
  form.find("form").props.onSubmit({preventDefault() {}});
  await form.finished();
  assert.equal(form.calls.length, 1);
  assert.equal(form.calls[0].method, "PUT");
  assert.equal(form.calls[0].revision, "4");
  assert.equal(form.calls[0].body.providerId, "replacement");
  assert.equal(form.calls[0].body.modelName, "changed-model");
  assert.equal(form.calls[0].body.capabilities.maxOutputTokens, 4000);
  assert.equal(form.calls[0].body.capabilities.maxContextTokens, 32000);
  assert.equal(form.calls[0].body.capabilities.supportsTools, false);
  assert.equal(form.calls[0].body.capabilities.protocolOptions.serviceTier, "priority");
  assert.equal(form.calls[0].body.capabilities.reasoningEfforts.length, 0);
  form.action.busy = true;
  assert.ok(form.nodes().filter((node) => node.type === "Fieldset" && node.props.className === "fields")
    .every((node) => node.props.disabled));
});

test("已使用连接可以修改协议和地址，空密钥仍表示保留原值", async () => {
  const form = component("model-provider-dialog", "ModelProviderDialog", {enterpriseId: "enterprise", provider,
    onClose() {}, onSaved() {}});
  assert.equal(form.find("Select").props.disabled, false);
  assert.equal(form.find("Input", (props) => props.type === "url").props.disabled, false);
  form.find("Select").props.onChange({target: {value: "openai_responses"}});
  form.find("Input", (props) => props.type === "url").props.onChange({target: {value: "https://replacement.example/v1"}});
  form.find("form").props.onSubmit({preventDefault() {}});
  await form.finished();
  assert.equal(form.calls[0].body.protocol, "openai_responses");
  assert.equal(form.calls[0].body.baseUrl, "https://replacement.example/v1");
  assert.equal(form.calls[0].body.apiKey, null);
  assert.equal(form.calls[0].revision, "3");
});

test("已使用模型的删除操作可用，确认后按当前版本请求删除", async () => {
  const page = component("model-management", "ModelManagement", {enterpriseId: "enterprise", canManage: true});
  const deletion = page.find("DropdownMenuItem", (props) => props.children.includes("删除模型"));
  assert.ok(deletion);
  assert.ok(!deletion.props.disabled);
  deletion.props.onClick();
  const confirmation = page.nodes().find((node) => typeof node.type === "function" && node.type.name === "DeleteModelConfiguration");
  const dialog = confirmation.type(confirmation.props);
  const button = page.nodes(dialog).find((node) => node.type === "Button" && node.props.children === "删除");
  button.props.onClick();
  await page.finished();
  assert.equal(page.calls[0].method, "DELETE");
  assert.equal(page.calls[0].revision, "4");
  assert.equal(page.calls[0].path, "/enterprises/enterprise/models/model");
  const readonly = component("model-management", "ModelManagement", {enterpriseId: "enterprise", canManage: false});
  assert.equal(readonly.find("DropdownMenuItem"), undefined);
});
