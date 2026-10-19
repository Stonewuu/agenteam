import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {loadSource} from "./i18n-test-runtime.mjs";
const extension = loadSource("features/edition/navigation-extension");
const paths = loadSource("features/auth/lib/workspace-path");

const source = readFileSync(new URL("../src/features/auth/lib/identity-navigation.ts", import.meta.url), "utf8");
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
function navigation(search = "", pathname = "/", hash = "", enterprise) {
  const exports = {};
  const require = name => name.endsWith("workspace-path") ? paths
    : name.endsWith("workspace-location-store") ? {activeEnterprise: () => enterprise} : extension;
  vm.runInNewContext(code, { exports, require, URL, URLSearchParams, window: { location: { origin: "http://localhost:3000", pathname, search, hash } } });
  return exports;
}
const user = { capabilities: [], enterprises: [{ id: "team-one" }, { id: "team-two" }], lastEnterpriseId: "team-two" };

test("企业入口编码编号，同一标签页使用短地址，首次进入仍准确选择企业", () => {
  const routes = navigation();
  assert.equal(routes.enterprisePath("space / name", "/employees"), "/open?enterprise=space+%2F+name&path=%2Femployees");
  assert.equal(routes.landingPath(user), "/open?enterprise=team-two&path=%2Fworkspace");
  assert.equal(navigation("", "/", "", "team-one").enterprisePath("team-one", "/management/members"), "/organization/members");
  assert.equal(routes.landingPath({ ...user, enterprises: [] }), "/no-enterprise");
});
test("登录返回位置保留新正式路径与查询条件", () => {
  const path = "/enterprises/team-one/capabilities/agents?query=测试";
  assert.equal(navigation(`?returnTo=${encodeURIComponent(path)}`).requestedDestination(user), "/enterprises/team-one/capabilities/agents?query=%E6%B5%8B%E8%AF%95");
});
test("外站、旧页面路径及不允许的返回位置不能改变登录去向", () => {
  for (const value of ["https://example.com/enterprises/test", "//example.com/settings", "/e/team-one/home", "/api/v1/auth/me", "javascript:alert(1)"]) {
    assert.equal(navigation(`?returnTo=${encodeURIComponent(value)}`).requestedDestination(user), "/open?enterprise=team-two&path=%2Fworkspace");
  }
});

test("会话过期后保存当前页面的路径、查询条件和页面定位", () => {
  const destination = "/enterprises/team-one/new-task?agent=employee-one#composer";
  const login = navigation("?agent=employee-one", "/enterprises/team-one/new-task", "#composer").loginPath();
  assert.equal(login, `/login?returnTo=${encodeURIComponent(destination)}`);
  assert.equal(navigation(login.slice("/login".length)).requestedDestination(user), destination);
});

test("个人设置重新登录后仍回到之前的分区", () => {
  const destination = "/settings?tab=security";
  const login = navigation().loginPath(destination);
  assert.equal(navigation(login.slice("/login".length)).requestedDestination(user), destination);
});

test("平台管理返回位置仅供系统超级管理员使用", () => {
  const routes = navigation("?returnTo=%2Fmanagement%2Fdemo");
  assert.equal(routes.requestedDestination({ ...user, superAdmin: true }), "/open?enterprise=team-two&path=%2Fworkspace");
  for (const item of extension.editionNavigationExtension.platform) {
    const route = navigation(`?returnTo=${encodeURIComponent(item.path)}`);
    assert.equal(route.requestedDestination({...user, superAdmin: true, capabilities: [item.capability]}), item.path);
  }
  assert.equal(routes.requestedDestination({ ...user, superAdmin: false }), "/open?enterprise=team-two&path=%2Fworkspace");
});

test("短地址支持详情定位，企业入口不接受外站或接口地址", () => {
  for (const path of ["/workspace", "/organization/members", "/conversations/chat-1?tab=files"]) {
    assert.equal(navigation(`?returnTo=${encodeURIComponent(path)}`).requestedDestination(user), path);
  }
  for (const value of ["//example.com", "/\\example.com", "/api/v1/auth/me", "/management/license"]) {
    assert.equal(paths.validWorkspaceDestination(value), false);
  }
  assert.deepEqual(paths.resourceDestination("/conversations/chat%201"), {type: "conversation", id: "chat 1"});
  assert.deepEqual(paths.resourceDestination("/capabilities/agents/agent-1"), {type: "agent", id: "agent-1"});
  assert.equal(paths.resourceDestination("/capabilities/agents"), null);
  assert.equal(paths.resourceDestination("/conversations/%ZZ"), null);
});

test("浏览历史恢复对应企业，标签页与登录身份互不覆盖", () => {
  const source = readFileSync(new URL("../src/features/auth/lib/workspace-location-store.ts", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText;
  function tab() {
    const storage = new Map();
    const window = {location: {href: "http://localhost/workspace"}, history: {state: {__NA: true},
      replaceState(value) {
        this.state = value;
      }}, sessionStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)}};
    const exports = {};
    vm.runInNewContext(compiled, {exports, window, console});
    return {window, ...exports};
  }
  const first = tab();
  const second = tab();
  first.rememberWorkspace("user", "rev-1", "team-one", "/workspace");
  const old = first.window.history.state;
  first.rememberWorkspace("user", "rev-1", "team-two", "/workspace");
  second.rememberWorkspace("user", "rev-1", "team-three", "/workspace");
  first.window.history.state = old;
  assert.equal(first.storedEnterprise("user", "rev-1", "/workspace"), "team-one");
  assert.equal(first.window.history.state.__NA, true);
  assert.equal(second.storedEnterprise("user", "rev-1", "/workspace"), "team-three");
  assert.equal(first.storedEnterprise("user", "rev-2", "/workspace"), undefined);
  assert.equal(first.storedEnterprise("another-user", "rev-1", "/workspace"), undefined);
});
