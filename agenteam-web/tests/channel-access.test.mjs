import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/lib/http/api-client.ts", import.meta.url), "utf8");
const code = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022}}).outputText;

function client() {
  const exports = {};
  const events = [];
  class CustomEvent {
    constructor(type, options) {
      this.type = type;
      this.detail = options.detail;
    }
  }
  vm.runInNewContext(code, {exports, require: () => ({}), CustomEvent, window: {dispatchEvent: (event) => events.push(event)}});
  return {exports, events};
}

test("本地重新认证保留当前企业页面，不触发丢失企业权限的事件", () => {
  const {exports: api, events} = client();
  api.notifyAccessFailure("/api/v1/enterprises/team-one/me/channels/binding", new api.ApiError(403, "LOCAL_REAUTH_REQUIRED", "请验证密码"));
  assert.equal(events.length, 0);
});

test("真正失去企业权限与会话失效仍立即通知页面停止操作", () => {
  const {exports: api, events} = client();
  api.notifyAccessFailure("/api/v1/enterprises/team-one/members", new api.ApiError(403, "FORBIDDEN", "没有权限"));
  assert.equal(events.length, 1);
  assert.equal(events[0].type, "agenteam:access-denied");
  assert.equal(events[0].detail.enterpriseId, "team-one");
  assert.equal(events[0].detail.loginRequired, false);
  api.notifyAccessFailure("/api/v1/enterprises/team-one/context", new api.ApiError(401, "AUTH_REQUIRED", "请登录"));
  assert.equal(events.length, 2);
  assert.equal(events[1].detail.loginRequired, true);
});
