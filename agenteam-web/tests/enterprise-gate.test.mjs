import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { setImmediate } from "node:timers/promises";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

function compile(path) {
  return ts.transpileModule(readFileSync(new URL(path, import.meta.url), "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
}

const gateCode = compile("../src/features/auth/components/enterprise-gate.tsx");
const navigationCode = compile("../src/features/auth/lib/identity-navigation.ts");
const user = { id: "user-one", preferences: { theme: "light" } };
const context = { enterprise: { id: "enterprise-one", timezone: "Asia/Shanghai" }, permissions: ["workspace.view"] };

class RequestError extends Error {
  constructor(status) {
    super(`请求返回 ${status}`);
    this.status = status;
  }
}

function createGate({ identity = async () => user, enterprise = async () => context, permission } = {}) {
  const states = [];
  const effects = [];
  const listeners = new Map();
  const redirects = [];
  let stateIndex = 0;
  let mounting = true;
  let childRenders = 0;
  const location = { origin: "http://localhost:3000", pathname: "/enterprises/enterprise-one/new-task", search: "?agent=employee-one", hash: "#composer" };
  const window = {
    location,
    addEventListener: (name, callback) => listeners.set(name, callback),
    removeEventListener: (name) => listeners.delete(name),
    setInterval: () => 1,
    clearInterval: () => undefined,
  };
  const navigation = {};
  vm.runInNewContext(navigationCode, { exports: navigation, require: () => ({ applyTheme: () => undefined }), window, URL, URLSearchParams });
  const markers = { loading: () => null, error: () => null };
  const jsx = (type, props) => ({ type, props });
  const exports = {};
  const imports = {
    react: {
      createContext: () => ({ Provider: "identity-provider" }),
      useContext: () => null,
      useState: (initial) => {
        const index = stateIndex++;
        if (mounting) {
          states[index] = initial;
        }
        return [states[index], (value) => {
          states[index] = typeof value === "function" ? value(states[index]) : value;
        }];
      },
      useEffect: (effect) => {
        if (mounting) {
          effects.push(effect);
        }
      },
    },
    "react/jsx-runtime": { jsx, jsxs: jsx },
    "next/navigation": { useRouter: () => ({ replace: (value) => redirects.push(value) }) },
    "next/link": { default: "a" },
    "@/components/ui/button": { Button: "button" },
    "@/features/workspace/components/workspace-loading": { WorkspaceLoading: markers.loading },
    "../api/identity-api": { loadIdentity: identity, loadEnterpriseContext: enterprise, rememberEnterprise: async () => undefined },
    "../lib/identity-navigation": navigation,
    "@/lib/http/api-client": { ApiError: RequestError, errorMessage: (error) => error.message },
    "./enterprise-error-page": { EnterpriseErrorPage: markers.error },
    "./enterprise-date-time": { EnterpriseTimezone: "timezone-provider" },
    "@/features/workspace/components/return-to-workspace": { RememberUserPage: "remember-page" },
  };
  vm.runInNewContext(gateCode, {
    exports,
    require: (name) => {
      assert.ok(name in imports, `测试尚未提供依赖：${name}`);
      return imports[name];
    },
    window,
    document: { hidden: false, addEventListener: () => undefined, removeEventListener: () => undefined },
    AbortController,
    console: { error: () => undefined },
  });
  const root = exports.EnterpriseGate({ enterpriseId: "enterprise-one", permission, children: () => {
    childRenders++;
    return "业务内容";
  } });
  function render() {
    stateIndex = 0;
    return root.type(root.props);
  }
  const initialView = render();
  mounting = false;
  const cleanup = effects.map((effect) => effect());
  return {
    initialView,
    render,
    markers,
    redirects,
    flush: () => setImmediate(),
    state: () => states[0],
    childRenders: () => childRenders,
    denied: (loginRequired) => listeners.get("agenteam:access-denied")({ detail: { enterpriseId: "enterprise-one", loginRequired } }),
    refresh: () => listeners.get("agenteam:identity-changed")(),
    dispose: () => cleanup.forEach((close) => close?.()),
  };
}

test("身份未确认时仅显示加载布局，不挂载业务内容", async () => {
  let finish;
  const gate = createGate({ identity: () => new Promise((resolve) => {
    finish = resolve;
  }) });
  assert.equal(gate.initialView.type, gate.markers.loading);
  assert.equal(gate.childRenders(), 0);
  gate.dispose();
  finish(user);
  await gate.flush();
  assert.equal(gate.state().kind, "loading");
});

test("确认未登录后跳转登录页并保存完整目标地址", async () => {
  const gate = createGate({ identity: async () => null });
  await gate.flush();
  assert.deepEqual(gate.redirects, ["/login?returnTo=%2Fenterprises%2Fenterprise-one%2Fnew-task%3Fagent%3Demployee-one%23composer"]);
  assert.equal(gate.childRenders(), 0);
  gate.dispose();
});

test("企业上下文读取期间会话失效同样进入登录页", async () => {
  const gate = createGate({ enterprise: async () => {
    throw new RequestError(401);
  } });
  await gate.flush();
  assert.equal(gate.redirects.length, 1);
  assert.equal(gate.state().kind, "loading");
  assert.equal(gate.childRenders(), 0);
  gate.dispose();
});

test("业务接口报告登录失效后移除旧页面且只跳转一次", async () => {
  const gate = createGate();
  await gate.flush();
  gate.render();
  assert.equal(gate.childRenders(), 1);
  gate.denied(true);
  gate.denied(true);
  assert.equal(gate.render().type, gate.markers.loading);
  assert.equal(gate.childRenders(), 1);
  assert.equal(gate.redirects.length, 1);
  gate.dispose();
});

test("登录失效后晚到的上下文结果不能重新显示业务页面", async () => {
  let finish;
  const gate = createGate({ enterprise: () => new Promise((resolve) => {
    finish = resolve;
  }) });
  await gate.flush();
  gate.denied(true);
  finish(context);
  await gate.flush();
  assert.equal(gate.render().type, gate.markers.loading);
  assert.equal(gate.childRenders(), 0);
  gate.dispose();
});

test("没有页面权限时保留访问错误，不转到登录页", async () => {
  const gate = createGate({ permission: "admin.view" });
  await gate.flush();
  assert.equal(gate.render().type, gate.markers.error);
  assert.equal(gate.redirects.length, 0);
  assert.equal(gate.childRenders(), 0);
  gate.dispose();
});

test("初次读取失败时在工作空间内显示重试，不转到登录页", async () => {
  const gate = createGate({ identity: async () => {
    throw new RequestError(503);
  } });
  await gate.flush();
  const view = gate.render();
  assert.equal(view.type, gate.markers.loading);
  assert.ok(view.props.error);
  assert.equal(typeof view.props.onRetry, "function");
  assert.equal(gate.redirects.length, 0);
  gate.dispose();
});

test("刷新暂时失败时保留已验证的页面，权限丢失后则移除内容", async () => {
  let requests = 0;
  const gate = createGate({ enterprise: async () => {
    if (requests++ > 0) {
      throw new RequestError(503);
    }
    return context;
  } });
  await gate.flush();
  gate.refresh();
  await gate.flush();
  assert.equal(gate.state().kind, "ready");
  assert.ok(gate.state().refreshError);
  assert.equal(gate.redirects.length, 0);
  gate.denied(false);
  assert.equal(gate.render().type, gate.markers.error);
  assert.equal(gate.redirects.length, 0);
  gate.dispose();
});
