import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {createRequire} from "node:module";
import {test} from "node:test";
import React from "react";
import {renderToStaticMarkup} from "react-dom/server";
import ts from "typescript";

const require = createRequire(import.meta.url);
const source = new URL("../src/features/integration/", import.meta.url);

function load(file, dependencies) {
  const code = ts.transpileModule(readFileSync(new URL(file, source), "utf8"), {compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX,
  }}).outputText;
  const loaded = {exports: {}};
  new Function("require", "module", "exports", "window", code)(name => dependencies[name] ?? require(name), loaded,
    loaded.exports, dependencies.window);
  return loaded.exports;
}

function render(query, {disabled = false, busy = false} = {}) {
  const buttons = [];
  const requests = [];
  const redirects = [];
  const text = (value, params = {}) => value.replace(/\{(\w+)\}/g, (_, name) => params[name]);
  const {ChannelLoginOptions} = load("components/channel-login-options.tsx", {
    "@/components/ui/button": {Button: props => {
      buttons.push(props);
      return React.createElement("button", props);
    }},
    "@/components/ui/icons": {IconArrowRight: () => null, IconBuilding: () => null, IconMessages: () => null},
    "@/components/ui/brand-icon": {BrandIcon: ({brand}) => React.createElement("span", {"data-brand":brand})},
    "@/components/ui/loading-transition": {LoadingTransition: ({children}) => React.createElement("div", null, children)},
    "@/features/auth/hooks/use-form-action": {useFormAction: () => ({busy, execute: operation => operation()})},
    "@/features/workspace/components/mutation-feedback": {MutationFeedback: () => null},
    "@/lib/http/use-api-query": {useApiQuery: path => {
      assert.equal(path, "/api/v1/auth/channel-login");
      return query;
    }},
    "@/lib/i18n/locale-provider": {useT: () => text},
    "../api/channel-login-api": {startChannelLogin: async key => {
      requests.push(key);
      return {authorizationUrl: "https://accounts.feishu.cn/test-authorize"};
    }},
    "./channel-login-options.module.css": new Proxy({}, {get: (_, key) => String(key)}),
    window: {location: {assign: url => redirects.push(url)}},
  });
  const html = renderToStaticMarkup(React.createElement(ChannelLoginOptions, {disabled, onBusyChange: () => {}}));
  return {html, buttons, requests, redirects};
}

const option = {key: "a".repeat(32), name: "企业内部名称", providerCode: "feishu", providerName: "飞书"};

test("未配置时不显示虚假的登录入口；加载和失败保留可恢复反馈", () => {
  assert.equal(render({data: [], loading: false, error: ""}).html, "<div></div>");
  assert.match(render({data: null, loading: true, error: ""}).html, /正在加载登录方式/);
  const retry = () => {};
  const failed = render({data: null, loading: false, error: "网络失败", retry});
  assert.match(failed.html, /暂时无法加载企业登录方式/);
  assert.equal(failed.buttons[0].onClick, retry);
});

test("单个飞书入口直接发起已有授权并导航到后端返回地址", async () => {
  const result = render({data: [option], loading: false, error: ""});
  assert.match(result.html, /通过 飞书 登录/);
  assert.match(result.html, /data-brand="feishu"/);
  assert.doesNotMatch(result.html, /企业内部名称/);
  result.buttons[0].onClick();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(result.requests, [option.key]);
  assert.deepEqual(result.redirects, ["https://accounts.feishu.cn/test-authorize"]);
});

test("同平台多个接入显示各自名称，账号密码提交或授权期间禁止重复点击", () => {
  const second = {...option, key: "b".repeat(32), name: "另一个团队"};
  const query = {data: [option, second], loading: false, error: ""};
  const result = render(query);
  assert.match(result.html, /企业内部名称/);
  assert.match(result.html, /另一个团队/);
  for (const state of [{disabled: true}, {busy: true}]) {
    assert.ok(render(query, state).buttons.every(button => button.disabled));
  }
});

test("共享授权调用使用原有防伪请求客户端，保留企业微信内嵌模式", async () => {
  const calls = [];
  const {startChannelLogin} = load("api/channel-login-api.ts", {
    "@/lib/http/api-client": {apiRequest: async (path, options) => {
      calls.push({path, options});
      return {authorizationUrl: "https://accounts.feishu.cn/test-authorize"};
    }},
  });
  await startChannelLogin(option.key);
  assert.equal(calls[0].path, `/api/v1/auth/channel-login/${option.key}`);
  assert.equal(calls[0].options.method, "POST");
  assert.equal(calls[0].options.body.embeddedClient, false);
});
