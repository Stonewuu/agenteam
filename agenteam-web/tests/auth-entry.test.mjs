import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/auth/api/identity-api.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;

class ResponseError extends Error {
  constructor(status, code = "INTERNAL_ERROR") {
    super("请求失败");
    this.status = status;
    this.code = code;
  }
}

function entry(request) {
  const exports = {};
  const calls = [];
  vm.runInNewContext(compiled, {
    exports,
    require: () => ({
      ApiError: ResponseError,
      ApiMutation: class {},
      apiRequest: async (path, options) => {
        calls.push(path);
        return request(path, options);
      },
    }),
  });
  return { load: exports.loadAuthEntry, calls };
}

test("已登录时直接返回身份，不请求系统初始化状态", async () => {
  const user = { id: "user-one" };
  const current = entry(async () => user);
  const result = await current.load();
  assert.equal(result.kind, "authenticated");
  assert.equal(result.user, user);
  assert.deepEqual(current.calls, ["/api/v1/auth/me"]);
});

test("确认未登录后才检查是否显示登录表单", async () => {
  const current = entry(async (path) => {
    if (path.endsWith("/me")) {
      throw new ResponseError(401);
    }
    return { initialized: true };
  });
  assert.equal((await current.load()).kind, "login");
  assert.deepEqual(current.calls, ["/api/v1/auth/me", "/api/v1/auth/bootstrap-status"]);
});

test("系统未初始化时按后端的 503 错误代码进入初始化表单", async () => {
  const current = entry(async (path) => {
    if (path.endsWith("/me")) {
      throw new ResponseError(503, "SYSTEM_NOT_INITIALIZED");
    }
    return { initialized: false };
  });
  assert.equal((await current.load()).kind, "setup");
  assert.deepEqual(current.calls, ["/api/v1/auth/me", "/api/v1/auth/bootstrap-status"]);
});

test("身份读取后其他浏览器完成初始化时使用最新状态显示登录表单", async () => {
  const current = entry(async (path) => {
    if (path.endsWith("/me")) {
      throw new ResponseError(503, "SYSTEM_NOT_INITIALIZED");
    }
    return { initialized: true };
  });
  assert.equal((await current.load()).kind, "login");
});

test("连接失败、服务故障和权限错误不会被识别为未登录", async () => {
  for (const status of [0, 403, 500, 503]) {
    const error = new ResponseError(status);
    const current = entry(async () => {
      throw error;
    });
    await assert.rejects(current.load(), (failure) => failure === error);
    assert.deepEqual(current.calls, ["/api/v1/auth/me"]);
  }
});

test("初始化状态读取失败时保留错误，不提前显示登录表单", async () => {
  const error = new ResponseError(503);
  const current = entry(async (path) => {
    if (path.endsWith("/me")) {
      throw new ResponseError(401);
    }
    throw error;
  });
  await assert.rejects(current.load(), (failure) => failure === error);
});

test("切换页面后取消身份读取，不再查询初始化状态", async () => {
  const controller = new AbortController();
  const current = entry(async () => {
    controller.abort();
    throw new ResponseError(401);
  });
  await assert.rejects(current.load(controller.signal), { name: "AbortError" });
  assert.deepEqual(current.calls, ["/api/v1/auth/me"]);
});

test("请求完成时已经取消，不再返回过期的登录结果", async () => {
  const controller = new AbortController();
  const current = entry(async () => {
    controller.abort();
    return { id: "user-one" };
  });
  await assert.rejects(current.load(controller.signal), { name: "AbortError" });
});

test("已经取消的读取不再发出请求", async () => {
  const controller = new AbortController();
  controller.abort();
  const current = entry(async () => ({ id: "user-one" }));
  await assert.rejects(current.load(controller.signal), { name: "AbortError" });
  assert.deepEqual(current.calls, []);
});
