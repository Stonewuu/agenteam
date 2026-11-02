import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

function reauthentication(failure = false) {
  let password = "";
  let pending;
  let revision = "old-session";
  const events = [];
  const modules = {
    react: {useState: () => [password, value => {
      password = value;
    }]},
    "react/jsx-runtime": {jsx: (type, props) => ({type, props}), jsxs: (type, props) => ({type, props})},
    "@/lib/i18n/locale-provider": {useT: () => value => value},
    "../hooks/use-form-action": {useFormAction: () => ({busy: false, fieldErrors: {}, execute: operation => {
      pending = operation();
      return pending.catch(() => {});
    }})},
    "@/lib/http/api-client": {async apiRequest(path, options) {
      assert.equal(path, "/api/v1/auth/reauthenticate");
      assert.equal(options.body.password, "entered-password");
      if (failure) {
        throw new Error("密码不正确");
      }
      return {identityRevision: "new-session"};
    }, clearCsrf: () => events.push("clear-csrf")},
    "@/lib/http/request-identity": {acceptRequestIdentity(value) {
      revision = value;
    },
      broadcastIdentityChange: () => events.push("notify-other-tabs")},
  };
  const exports = {};
  const source = readFileSync(new URL("../src/features/auth/components/local-reauthentication.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, {compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX,
  }}).outputText;
  vm.runInNewContext(compiled, {exports, Event, window: {dispatchEvent: event => events.push(event.type)}, require: name => modules[name] ?? new Proxy({}, {
    get: (_, key) => key === "default" ? {} : String(key),
  })});
  function find(value, type) {
    if (Array.isArray(value)) {
      return value.map(child => find(child, type)).find(Boolean);
    }
    if (!value || typeof value !== "object") {
      return undefined;
    }
    return value.type === type ? value : find(value.props?.children, type);
  }
  const render = () => exports.LocalReauthentication({onClose() {}, onSuccess() {
    events.push({resumedWith: revision});
  }});
  find(render(), "Input").props.onChange({target: {value: "entered-password"}});
  find(render(), "DialogForm").props.onSubmit({preventDefault() {}});
  return {events, finished: () => pending, password: () => password};
}

test("密码验证后先采用新会话标识，再恢复原绑定操作", async () => {
  const view = reauthentication();
  await view.finished();
  assert.deepEqual(view.events, ["clear-csrf", "notify-other-tabs", "agenteam:identity-changed", {resumedWith: "new-session"}]);
  assert.equal(view.password(), "");
});

test("密码验证失败保留输入，不更新身份或继续操作", async () => {
  const view = reauthentication(true);
  await assert.rejects(view.finished(), /密码不正确/);
  assert.deepEqual(view.events, []);
  assert.equal(view.password(), "entered-password");
});
