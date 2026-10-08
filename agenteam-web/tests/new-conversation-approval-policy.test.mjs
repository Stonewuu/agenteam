import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

function loadHook(file, scope, modules) {
  const source = readFileSync(new URL(`../src/features/agent/hooks/${file}.ts`, import.meta.url), "utf8");
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText,
    { ...scope, exports, require: (name) => modules[name] });
  return exports;
}

function setup(values = new Map()) {
  const events = new Map(), logs = [], states = [], requests = [];
  const storage = { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) };
  let cursor = 0;
  const subscriptions = [];
  const react = {
    useCallback: (callback) => callback,
    useSyncExternalStore: (subscribe, snapshot) => {
      subscriptions.push(subscribe);
      return snapshot();
    },
    useRef: (current) => ({ current }),
    useState(initial) {
      const index = cursor++;
      if (!(index in states)) {
        states[index] = typeof initial === "function" ? initial() : initial;
      }
      return [states[index], (value) => {
        states[index] = typeof value === "function" ? value(states[index]) : value;
      }];
    },
  };
  const scope = { console: { error: (...args) => logs.push(args) }, window: {
    localStorage: storage,
    addEventListener: (name, callback) => {
      const listeners = events.get(name) ?? new Set();
      listeners.add(callback);
      events.set(name, listeners);
    },
    removeEventListener: (name, callback) => events.get(name)?.delete(callback),
    dispatchEvent: (event) => events.get(event.type)?.forEach((callback) => callback(event)),
  }, CustomEvent: class {
    constructor(type, options) {
      this.type = type;
      this.detail = options.detail;
    }
  } };
  const preference = loadHook("use-new-conversation-approval-policy", scope, { react });
  const existing = loadHook("use-conversation-approval-policy", scope, { react,
    "@/lib/i18n/locale-provider": { useT: () => (message) => message },
    "./use-new-conversation-approval-policy": preference,
    "../api/conversation-api": { conversationPath: (enterprise, id) => `/enterprises/${enterprise}/conversations/${id}` },
    "@/lib/http/api-client": { errorMessage: (error) => error.message, ApiMutation: class {
      async run(path, options) {
        requests.push({ path, options });
        return { id: "existing", revision: "2", mode: "normal", status: "active", approvalPolicy: options.body.approvalPolicy };
      }
    } },
  });
  const render = (props = {}) => {
    cursor = 0;
    return existing.useConversationApprovalPolicy({ userId: "user", enterprise: "enterprise", draftKey: "new", conversationId: null, enabled: true, busy: false, onChanged: () => {}, ...props });
  };
  return { ...preference, render, scope, values, storage, logs, requests, subscriptions };
}

test("未保存偏好时选择自动批准，已保存的三种模式刷新后均保留", () => {
  const e = setup();
  assert.equal(e.readNewConversationPolicy("user", "enterprise"), "auto_approve");
  assert.equal(e.render().value, "auto_approve");
  assert.equal(e.values.size, 0, "读取初始选择不写入或覆盖用户偏好");
  for (const policy of ["default", "auto_approve", "full_access"]) {
    e.saveNewConversationPolicy("user", "enterprise", policy);
    const reloaded = setup(e.values);
    assert.equal(reloaded.readNewConversationPolicy("user", "enterprise"), policy);
    assert.equal(reloaded.render().value, policy);
  }
});

test("升级后保留旧版用户偏好，新的选择优先且不改写旧记录", () => {
  const legacyKey = "station:new-conversation-approval-policy:user:enterprise";
  for (const policy of ["default", "auto_approve", "full_access"]) {
    const e = setup(new Map([[legacyKey, policy]]));
    assert.equal(e.readNewConversationPolicy("user", "enterprise"), policy);
    assert.equal(e.render().value, policy);
    assert.equal(e.readNewConversationPolicy("other", "enterprise"), "auto_approve");
    assert.equal(e.readNewConversationPolicy("user", "other"), "auto_approve");
    e.saveNewConversationPolicy("user", "enterprise", "default");
    assert.equal(setup(e.values).readNewConversationPolicy("user", "enterprise"), "default");
    assert.equal(e.values.get(legacyKey), policy);
  }
});

test("其他标签页保存旧版偏好时通知当前入口", () => {
  const e = setup();
  e.useNewConversationApprovalPolicy("user", "enterprise");
  let changes = 0;
  const unsubscribe = e.subscriptions[0](() => {
    changes++;
  });
  const key = "station:new-conversation-approval-policy:user:enterprise";
  e.values.set(key, "full_access");
  e.scope.window.dispatchEvent({ type: "storage", key });
  assert.equal(changes, 1);
  assert.equal(e.readNewConversationPolicy("user", "enterprise"), "full_access");
  unsubscribe();
});

test("按用户与企业隔离，特殊分隔字符不会串用偏好", () => {
  const e = setup();
  e.saveNewConversationPolicy("user", "enterprise", "full_access");
  assert.equal(e.readNewConversationPolicy("other", "enterprise"), "auto_approve");
  assert.equal(e.readNewConversationPolicy("user", "other"), "auto_approve");
  e.saveNewConversationPolicy("a:b", "c", "default");
  assert.equal(e.readNewConversationPolicy("a", "b:c"), "auto_approve");
});

test("未知或损坏的存储值不会自动启用更宽松的模式", () => {
  const e = setup();
  e.saveNewConversationPolicy("user", "enterprise", "default");
  const key = [...e.values.keys()][0];
  for (const value of ["true", "admin", "FULL_ACCESS", "null", ""]) {
    e.values.set(key, value);
    assert.equal(e.readNewConversationPolicy("user", "enterprise"), "default");
  }
});

test("存储不可写时当前页面仍可沿用选择，并记录异常原因", () => {
  const e = setup();
  const failure = new Error("存储不可用");
  e.storage.setItem = () => {
    throw failure;
  };
  e.saveNewConversationPolicy("user", "enterprise", "full_access");
  e.saveNewConversationPolicy("user", "enterprise", "auto_approve");
  assert.equal(e.readNewConversationPolicy("user", "enterprise"), "auto_approve");
  assert.equal(e.logs.length, 1);
  assert.equal(e.logs[0][1], failure);
});

test("同页选择立即通知其他入口，其他企业的变更不会触发更新", () => {
  const e = setup();
  e.useNewConversationApprovalPolicy("user", "enterprise");
  let changes = 0;
  const unsubscribe = e.subscriptions[0](() => {
    changes++;
  });
  e.saveNewConversationPolicy("user", "other", "auto_approve");
  assert.equal(changes, 0);
  e.saveNewConversationPolicy("user", "enterprise", "full_access");
  assert.equal(changes, 1);
  unsubscribe();
  e.saveNewConversationPolicy("user", "enterprise", "default");
  assert.equal(changes, 1);
});

test("新对话选择后，清空草稿和创建下一段对话仍沿用选择", async () => {
  const e = setup();
  await e.render().change("full_access");
  e.render().resetDraft();
  assert.equal(e.render({ draftKey: "next" }).value, "full_access");
  assert.equal(e.readNewConversationPolicy("user", "enterprise"), "full_access");
  assert.equal(e.requests.length, 0);
});

test("已有对话读取和修改自身模式，不覆盖新对话的偏好", async () => {
  const e = setup();
  e.saveNewConversationPolicy("user", "enterprise", "full_access");
  const props = { conversationId: "existing", conversation: { id: "existing", revision: "1", mode: "normal", status: "active", approvalPolicy: "default" } };
  assert.equal(e.render(props).value, "default");
  await e.render(props).change("auto_approve");
  assert.equal(e.render(props).value, "auto_approve");
  assert.equal(e.requests[0].options.body.approvalPolicy, "auto_approve");
  assert.equal(e.readNewConversationPolicy("user", "enterprise"), "full_access");
});

test("等待已有对话加载时不采用新对话的偏好，忙碌时也不能改变偏好", async () => {
  const e = setup();
  e.saveNewConversationPolicy("user", "enterprise", "full_access");
  assert.equal(e.render({ conversationId: "existing" }).value, "default");
  assert.equal(e.render({ conversationId: "existing", initialPolicy: "auto_approve" }).value, "auto_approve");
  await e.render({ busy: true }).change("default");
  assert.equal(e.readNewConversationPolicy("user", "enterprise"), "full_access");
});
