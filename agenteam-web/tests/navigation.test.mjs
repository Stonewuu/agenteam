import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {loadSource} from "./i18n-test-runtime.mjs";
const extension = loadSource("features/edition/navigation-extension");

const source = readFileSync(new URL("../src/features/auth/lib/identity-navigation.ts", import.meta.url), "utf8");
function navigation(search = "", pathname = "/", hash = "") {
  const exports = {};
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
  vm.runInNewContext(code, { exports, require: () => extension, URL, URLSearchParams, window: { location: { origin: "http://localhost:3000", pathname, search, hash } } });
  return exports;
}
const user = { capabilities: [], enterprises: [{ id: "team-one" }, { id: "team-two" }], lastEnterpriseId: "team-two" };

test("企业路径编码编号，并默认打开用户上次访问的企业", () => {
  const routes = navigation();
  assert.equal(routes.enterprisePath("space / name", "/employees"), "/enterprises/space%20%2F%20name/employees");
  assert.equal(routes.landingPath(user), "/enterprises/team-two/workspace");
  assert.equal(routes.landingPath({ ...user, enterprises: [] }), "/no-enterprise");
});
test("登录返回位置保留新正式路径与查询条件", () => {
  const path = "/enterprises/team-one/capabilities/agents?query=测试";
  assert.equal(navigation(`?returnTo=${encodeURIComponent(path)}`).requestedDestination(user), "/enterprises/team-one/capabilities/agents?query=%E6%B5%8B%E8%AF%95");
});
test("外站、旧页面路径及不允许的返回位置不能改变登录去向", () => {
  for (const value of ["https://example.com/enterprises/test", "//example.com/settings", "/e/team-one/home", "/api/v1/auth/me", "javascript:alert(1)"]) {
    assert.equal(navigation(`?returnTo=${encodeURIComponent(value)}`).requestedDestination(user), "/enterprises/team-two/workspace");
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
  assert.equal(routes.requestedDestination({ ...user, superAdmin: true }), "/enterprises/team-two/workspace");
  for (const item of extension.editionNavigationExtension.platform) {
    const route = navigation(`?returnTo=${encodeURIComponent(item.path)}`);
    assert.equal(route.requestedDestination({...user, superAdmin: true, capabilities: [item.capability]}), item.path);
  }
  assert.equal(routes.requestedDestination({ ...user, superAdmin: false }), "/enterprises/team-two/workspace");
});
