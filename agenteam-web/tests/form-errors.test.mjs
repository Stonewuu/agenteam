import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { existsSync, statSync } from "node:fs";
import { posix } from "node:path";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";

const moduleUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
const css = moduleUrl("export default new Proxy({}, { get: (_, name) => name });");
async function compile(path, imports = {}) {
  const source = await readFile(new URL(`../src/${path}`, import.meta.url), "utf8");
  if (path.endsWith(".json")) {
    return moduleUrl(`export default ${source};`);
  }
  let result = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022, jsx: ts.JsxEmit.ReactJSX,
  } }).outputText;
  const resolved = { ...imports };
  for (const [, name] of result.matchAll(/from "([^"]+)"/g)) {
    if (resolved[name]) {
continue;
}
    if (name.endsWith(".css")) {
resolved[name] = css;
} else if (name.startsWith("@/") || name.startsWith(".")) {
      const local = name.startsWith("@/") ? name.slice(2) : posix.normalize(`${posix.dirname(path)}/${name}`);
      const target = [local, `${local}.tsx`, `${local}.ts`].find((value) => existsSync(new URL(`../src/${value}`, import.meta.url)) && statSync(new URL(`../src/${value}`, import.meta.url)).isFile());
      if (!target) {
throw new Error(`找不到测试使用的源码：${local}`);
}
      resolved[name] = await compile(target);
    } else {
resolved[name] = import.meta.resolve(name);
}
  }
  for (const [name, target] of Object.entries(resolved)) {
result = result.replaceAll(`from "${name}"`, `from "${target}"`);
}
  return moduleUrl(result);
}
const agent = await compile("features/resource/lib/agent-config-input.ts");
const { agentConfigInput } = await import(agent);
const workflow = await compile("features/workflow/lib/workflow-graph.ts");
const appearance = await compile("components/ui/resource-appearance.ts");
const appearanceValues = await import(appearance);
const resource = await import(await compile("features/resource/lib/resource-display.ts", {
  "@/components/ui/resource-appearance": appearance, "./agent-config-input": agent, "@/features/workflow/lib/workflow-graph": workflow,
}));

test("六类资源创建时分别随机初始化外观，读取和保存已有资源时保留外观", (context) => {
  const { resourceIcons, resourceColors } = appearanceValues;
  let index = 0;
  context.mock.method(crypto, "getRandomValues", (values) => {
 values.set([index, index]); index++; return values; 
});
  for (const [at, kind] of ["agent", "skill", "plugin", "workflow", "knowledge", "data"].entries()) {
    const config = resource.newConfig(kind);
    assert.equal(config.icon, resourceIcons[at][0]);
    assert.equal(config.color, resourceColors[at][0]);
    const detail = { resource: { kind, name: "保留外观", description: "", tags: [] }, draft: config };
    assert.deepEqual(resource.draftFrom(detail).config, config);
  }
  assert.equal(index, 6, "读取已有资源不会再次抽取图标或颜色");
});

test("保存智能体时清除旧研究开关和手填助手，不转换成任何可调用助手", () => {
  const old = { ...resource.newConfig("agent"), researchSubagentEnabled: true, subagents: [{ id: "writer", name: "旧助手", instructions: "旧指令" }] };
  const saved = agentConfigInput(old);
  assert.equal(saved.researchSubagentEnabled, undefined);
  assert.equal(saved.subagents, undefined);
  assert.deepEqual(saved.subagentVersionIds, []);
  assert.equal(saved.dynamicSubagentEnabled, false);
  assert.equal(old.subagents.length, 1);
});
test("已选智能体只保存固定版本引用，可以与临时助手同时启用", () => {
  const value = { ...resource.newConfig("agent"), subagentVersionIds: ["writer-version", "reviewer-version"], dynamicSubagentEnabled: true };
  const saved = agentConfigInput(value);
  assert.deepEqual(saved.subagentVersionIds, ["writer-version", "reviewer-version"]);
  assert.equal(saved.subagents, undefined);
  assert.equal(saved.dynamicSubagentEnabled, true);
  assert.deepEqual(agentConfigInput({ ...saved, subagentVersionIds: [] }).subagentVersionIds, []);
});
const errors = await compile("lib/form-errors.ts");
const apiModule = await compile("lib/http/api-client.ts", { "@/lib/form-errors": errors });
const api = await import(apiModule);
const { loadIdentity, loadAuthEntry } = await import(await compile("features/auth/api/identity-api.ts", {
  "@/lib/http/api-client": apiModule,
}));
const feedback = await compile("components/ui/error-feedback.tsx", {
  "react": import.meta.resolve("react"), "react/jsx-runtime": import.meta.resolve("react/jsx-runtime"),
  "@/lib/form-errors": errors, "./surface.module.css": css,
});
const { MutationFeedback } = await import(await compile("features/workspace/components/mutation-feedback.tsx", {
  "react/jsx-runtime": import.meta.resolve("react/jsx-runtime"), "@/components/ui/error-feedback": feedback,
  "@/components/ui/surface.module.css": css,
}));

test("智能体草稿读取和保存只保留当前字段，额外字段不会重新提交", () => {
  const config = { ...resource.newConfig("agent"), modelProfileId: "model", instructions: "整理资料", temperature: 0.4,
    maxOutputTokens: 204800, extraField: "不属于表单" };
  const draft = { name: "资料助手", description: "", tagIds: [], config };
  const saved = resource.draftInput("agent", draft);
  assert.equal(saved.config.maxOutputTokens, undefined);
  assert.equal(saved.config.extraField, undefined);
  assert.equal(saved.config.temperature, 0.4);
  assert.equal(saved.config.modelProfileId, "model");
  const loaded = resource.draftFrom({ resource: { kind: "agent", name: draft.name, description: "", tags: [] }, draft: config });
  assert.deepEqual(loaded, saved);
  assert.equal(config.maxOutputTokens, 204800, "不改写读取结果或持久数据");
  assert.equal(resource.draftInput("skill", draft).config, config, "其他资源沿用原有字段结构");
});

test("接口异常保留原始字段与请求编号，页面只显示可操作的中文提示", async () => {
  const response = new Response(JSON.stringify({ error: { code: "VALIDATION_FAILED", message: "有 2 个字段需要修改，请查看具体提示。",
    fieldErrors: { "config.maxOutputTokens": ["不支持此字段，请刷新页面后重新提交。"], enabled: ["需要填写布尔值（true 或 false）。"] },
    requestId: "request-123", details: {} } }), { status: 422 });
  await assert.rejects(api.parseApiResponse(response), (error) => {
    assert.equal(error.requestId, "request-123");
    assert.equal(error.status, 422);
    const message = api.errorMessage(error);
    assert.deepEqual(error.fieldErrors["config.maxOutputTokens"], ["不支持此字段，请刷新页面后重新提交。"]);
    assert.match(message, /不支持此字段，请刷新页面后重新提交/);
    assert.match(message, /启用状态：请重新选择此选项/);
    assert.doesNotMatch(message, /config\.maxOutputTokens|布尔值|request-123/);
    return true;
  });
});

test("能力中心错误反馈保留具体原因与焦点入口，不显示内部编号或字段代码", () => {
  const html = renderToStaticMarkup(React.createElement(MutationFeedback, { action: {
    error: "保存失败，请修改以下字段。", message: "", conflict: false, busy: false,
    fieldErrors: { "capabilities.maxOutputTokens": ["不能小于 128。"] }, requestId: "request-456",
  } }));
  assert.match(html, /role="alert"/);
  assert.match(html, /data-form-error="true"/);
  assert.doesNotMatch(html, /操作未完成|保存失败，请修改以下字段/);
  assert.match(html, /输出长度上限/);
  assert.match(html, /不能小于 128/);
  assert.doesNotMatch(html, /request-456|capabilities\.maxOutputTokens/);
});

test("字段校验只显示一次中文原因，移除接口字段名和重复条目", () => {
  const html = renderToStaticMarkup(React.createElement(MutationFeedback, { action: {
    error: "字段「name」：字符数需要在 1～80 之间。", conflict: false, busy: false,
    fieldErrors: { name: ["字符数需要在 1～80 之间。", "字符数需要在 1～80 之间。"] },
  } }));
  assert.equal((html.match(/名称：请输入 1～80 个字符。/g) ?? []).length, 1);
  assert.doesNotMatch(html, /字段「name」|操作未完成|字符数需要在/);
  assert.match(html, /role="alert"/);
});

test("字段校验的错误文本不会再次拼入原始摘要，异常数据仍保留", () => {
  const original = "字段「name」：字符数需要在 1～80 之间。";
  const error = new api.ApiError(422, "VALIDATION_FAILED", original, { name: ["字符数需要在 1～80 之间。"] });
  assert.equal(api.errorMessage(error), "名称：请输入 1～80 个字符。");
  assert.equal(error.message, original);
});

test("没有字段错误时保留整体失败原因和重新加载入口", () => {
  const html = renderToStaticMarkup(React.createElement(MutationFeedback, { action: {
    error: "其他人已修改此内容，请先重新加载。", conflict: true, busy: false, fieldErrors: {},
  }, onReload: () => {} }));
  assert.match(html, /其他人已修改此内容，请先重新加载/);
  assert.match(html, /重新加载最新内容/);
  assert.doesNotMatch(html, /操作未完成/);
});

test("多个字段的中文提示分别保留，无错误时不呈现提示", () => {
  const html = renderToStaticMarkup(React.createElement(MutationFeedback, { action: {
    error: "校验失败。", conflict: false, busy: false,
    fieldErrors: { name: ["请填写名称。"], "config.instructions": ["请填写执行指令。"] },
  } }));
  assert.match(html, /请填写名称/);
  assert.match(html, /执行指令/);
  assert.equal((html.match(/role="listitem"/g) ?? []).length, 2);
  const empty = renderToStaticMarkup(React.createElement(MutationFeedback, { action: {
    error: "", conflict: false, busy: false, fieldErrors: {},
  } }));
  assert.doesNotMatch(empty, /role="alert"|需要修改的内容|errorFeedback/);
});

test("异常日志保留原始原因与请求定位信息，移除查询参数", () => {
  const original = new Error("连接中断");
  const error = new api.ApiError(0, "NETWORK_ERROR", "暂时无法连接。", {}, {}, "request-789", original);
  const entries = []; const previous = console.error;
  console.error = (...args) => entries.push(args);
  try {
 api.reportRequestFailure(error, "GET", "/api/v1/files/test/content?token=private-test-value"); 
} finally {
 console.error = previous; 
}
  assert.equal(error.cause, original);
  assert.deepEqual(entries[0][1], { method: "GET", path: "/api/v1/files/test/content", requestId: "request-789" });
  assert.match(entries[0][2], /连接中断/);
  assert.doesNotMatch(JSON.stringify(entries), /private-test-value/);
});

test("未登录时返回空身份且不记录错误，服务端和网络故障仍抛出并记录", async (t) => {
  const logger = t.mock.method(console, "error", () => {});
  let status = 401;
  const connectionError = new TypeError("连接中断");
  t.mock.method(globalThis, "fetch", async () => {
    if (status === 0) {
throw connectionError;
}
    return Response.json({ error: {
      code: status === 401 ? "UNAUTHENTICATED" : "INTERNAL_ERROR",
      message: status === 401 ? "请先登录" : "服务暂时不可用。",
      requestId: "identity-test",
    } }, { status });
  });
  const previousWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout };
  try {
    assert.equal(await loadIdentity(), null);
    assert.equal(logger.mock.callCount(), 0, "正常未登录不应触发开发错误弹层");

    status = 500;
    await assert.rejects(loadIdentity(), { status: 500, code: "INTERNAL_ERROR" });
    assert.equal(logger.mock.callCount(), 1);
    assert.deepEqual(logger.mock.calls[0].arguments[1], { method: "GET", path: "/api/v1/auth/me", requestId: "identity-test" });

    status = 0;
    await assert.rejects(loadIdentity(), { status: 0, code: "NETWORK_ERROR", cause: connectionError });
    assert.equal(logger.mock.callCount(), 2);
    assert.match(logger.mock.calls[1].arguments[2], /连接中断/);
  } finally {
    if (previousWindow === undefined) {
delete globalThis.window;
} else {
globalThis.window = previousWindow;
}
  }
});

test("空数据库的身份响应进入初始化表单，其他 503 仍然抛出并记录", async (t) => {
  const logger = t.mock.method(console, "error", () => {});
  const requests = [];
  let code = "SYSTEM_NOT_INITIALIZED";
  t.mock.method(globalThis, "fetch", async (path) => {
    requests.push(path);
    if (path === "/api/v1/auth/bootstrap-status") {
      return Response.json({ data: { initialized: false } });
    }
    return Response.json({ error: { code, message: "系统尚未初始化，请联系部署人员。" } }, { status: 503 });
  });
  const previousWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout };
  try {
    assert.equal((await loadAuthEntry()).kind, "setup");
    assert.deepEqual(requests, ["/api/v1/auth/me", "/api/v1/auth/bootstrap-status"]);
    assert.equal(logger.mock.callCount(), 0);

    await assert.rejects(api.apiRequest("/api/v1/auth/login", { method: "GET" }), { status: 503, code });
    assert.equal(logger.mock.callCount(), 1, "其他接口仍保留未初始化错误，不能统一隐藏");

    code = "SERVICE_UNAVAILABLE";
    await assert.rejects(loadIdentity(), { status: 503, code });
    assert.equal(logger.mock.callCount(), 2, "服务故障不能当作正常初始化状态");
  } finally {
    if (previousWindow === undefined) {
      delete globalThis.window;
    } else {
      globalThis.window = previousWindow;
    }
  }
});

test("企业请求登录失效时仍抛出异常并通知登录入口，不记录控制台错误", async (t) => {
  const logger = t.mock.method(console, "error", () => {});
  t.mock.method(globalThis, "fetch", async () => Response.json({ error: {
    code: "UNAUTHENTICATED", message: "请先登录",
  } }, { status: 401 }));
  const events = [], previousWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout, dispatchEvent: (event) => {
 events.push(event); return true; 
} };
  try {
    await assert.rejects(api.apiRequest("/api/v1/enterprises/team%20one/context"), {
      status: 401, code: "UNAUTHENTICATED", message: "请先登录",
    });
    assert.equal(events.length, 1);
    assert.equal(events[0].type, "agenteam:access-denied");
    assert.deepEqual(events[0].detail, { enterpriseId: "team one", loginRequired: true });
    assert.equal(logger.mock.callCount(), 0);
  } finally {
    if (previousWindow === undefined) {
delete globalThis.window;
} else {
globalThis.window = previousWindow;
}
  }
});

test("字段变化在当前数据页处理，真实登录与企业权限失效仍通知入口", () => {
  const events = [], previousWindow = globalThis.window;
  globalThis.window = { dispatchEvent: (event) => {
 events.push(event); return true; 
} };
  const path = "/api/v1/enterprises/team%20one/data/collection/query";
  try {
    api.notifyAccessFailure(path, new api.ApiError(403, "DATA_FIELD_UNAVAILABLE", "所选字段已不可读取，请重新选择。"));
    api.notifyAccessFailure(path, new api.ApiError(409, "DATA_GENERATION_CHANGED", "请刷新集合。"));
    assert.equal(events.length, 0, "字段或内容变化不应卸载整个企业工作空间");

    for (const [status, code, loginRequired] of [
      [401, "UNAUTHENTICATED", true],
      [401, "DATA_FIELD_UNAVAILABLE", true],
      [403, "PERMISSION_DENIED", false],
      [404, "ENTERPRISE_UNAVAILABLE", false],
    ]) {
      api.notifyAccessFailure(path, new api.ApiError(status, code, "当前操作不可用。"));
      const event = events.at(-1);
      assert.equal(event.type, "agenteam:access-denied");
      assert.deepEqual(event.detail, { enterpriseId: "team one", loginRequired });
    }
    assert.equal(events.length, 4);
    api.notifyAccessFailure("/api/v1/auth/me", new api.ApiError(403, "PERMISSION_DENIED", "当前操作不可用。"));
    assert.equal(events.length, 4, "没有企业上下文的请求不应误通知某个企业");
  } finally {
    if (previousWindow === undefined) {
delete globalThis.window;
} else {
globalThis.window = previousWindow;
}
  }
});
