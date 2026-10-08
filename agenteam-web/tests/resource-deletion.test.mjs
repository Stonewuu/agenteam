import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import jsxRuntime from "react/jsx-runtime";
import ts from "typescript";
import { chineseText, localizeCatalog, loadSource } from "./i18n-test-runtime.mjs";

const { localizeUiMessage } = loadSource("lib/i18n/ui-message");

const display = {};
vm.runInNewContext(ts.transpileModule(readFileSync(new URL("../src/features/resource/lib/resource-display.ts", import.meta.url), "utf8"), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: display, require: () => ({}) });

function component(file, values) {
  const source = readFileSync(new URL(`../src/features/resource/components/${file}`, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX,
  } }).outputText;
  const exports = {};
  const wrapper = (tag) => function Wrapper({ children, ...props }) {
    return React.createElement(tag, props, children);
  };
  const ui = {
    useT: () => chineseText,
    localizeCatalog,
    localizeUiMessage,
    Button: (props) => {
      values.buttons.push(props);
      return React.createElement("button", props, props.children);
    },
    Fieldset: wrapper("fieldset"),
    Dialog: ({ title, children }) => React.createElement("section", { "aria-label": title }, children),
    DialogActions: wrapper("div"),
    DialogCancel: wrapper("button"),
    useDialogControl: () => ({ ref: { current: null }, close: (callback) => callback() }),
    QueryState: ({ children }) => children,
    MutationFeedback: () => null,
    ResourceAvatar: () => null,
    VersionSelectionDialog: () => null,
    organizationPath: (enterprise, path) => `/api/v1/enterprises/${enterprise}${path}`,
    useApiQuery: () => ({ ...values.query, retry: () => {} }),
    useFormAction: () => ({
      busy: false,
      mutation: { run: async (...request) => {
        values.request = request;
      } },
      execute: (operation) => {
        values.pending = operation();
        return values.pending;
      },
    }),
  };
  vm.runInNewContext(compiled, { exports, require: (name) => {
    if (name === "react") {
      return React;
    }
    if (name === "react/jsx-runtime") {
      return jsxRuntime;
    }
    if (name.endsWith(".css")) {
      return { default: new Proxy({}, { get: (_, key) => key }) };
    }
    if (name === "../lib/resource-display") {
      return display;
    }
    return ui;
  } });
  return exports;
}

const resource = { id: "plugin", revision: "2", name: "工具合集", kind: "plugin", status: "active" };
const impact = {
  resourceId: "plugin", revision: "3", visibleDependencies: [{ id: "agent", name: "测试员工" }],
  hiddenDependencyCount: 0, activeHireCount: 0, enabledScheduleCount: 0, activeRunCount: 0, canDelete: true,
};

function lifecycle(value = impact, current = resource, operation = "delete") {
  const values = { buttons: [], query: { data: value, loading: false, error: "" } };
  const { ResourceLifecycleDialog } = component("resource-lifecycle-dialog.tsx", values);
  const html = renderToStaticMarkup(React.createElement(ResourceLifecycleDialog, {
    enterpriseId: "enterprise", resource: current, operation, onClose: () => {}, onChanged: () => {},
  }));
  return { values, html, button: values.buttons.find((button) => typeof button.children === "string" && /^(删除|恢复|启用|停用)/.test(button.children)) };
}

test("存在发布引用时仍能提交删除，并使用影响查询返回的最新修改编号", async () => {
  const result = lifecycle();
  assert.equal(result.button.disabled, false);
  assert.match(result.html, /测试员工/);
  assert.match(result.html, /相关能力将不可用/);
  assert.doesNotMatch(result.html, /未结束的执行：0|已启用的计划：0|请先处理正在使用/);
  result.button.onClick();
  await result.values.pending;
  assert.equal(result.values.request[0], "/api/v1/enterprises/enterprise/resources/plugin");
  assert.equal(result.values.request[1].method, "DELETE");
  assert.equal(result.values.request[1].revision, "3");
});

test("删除前说明计划暂停，未结束的任务仍阻止删除", () => {
  const withPlans = lifecycle({ ...impact, enabledScheduleCount: 2 });
  assert.equal(withPlans.button.disabled, false);
  assert.match(withPlans.html, /将暂停 2 个相关计划/);
  const running = lifecycle({ ...impact, activeRunCount: 1, canDelete: false });
  assert.equal(running.button.disabled, true);
  assert.match(running.html, /请先停止未结束的任务/);
  const noAccess = lifecycle({ ...impact, canDelete: false });
  assert.equal(noAccess.button.disabled, true);
  assert.match(noAccess.html, /当前无法删除此插件，请重新加载/);
  assert.doesNotMatch(noAccess.html, /请先停止未结束的任务|请先解除/);
});

test("删除已雇佣智能体无需手动解除，暂停雇佣也会一并解除", async () => {
  const result = lifecycle({ ...impact, activeHireCount: 3 }, { ...resource, kind: "agent" });
  assert.equal(result.button.children, "删除智能体");
  assert.equal(result.button.disabled, false);
  assert.match(result.html, /自动解除所有人的雇佣，包括已暂停的雇佣/);
  assert.match(result.html, /恢复后需要重新雇佣/);
  assert.doesNotMatch(result.html, /请先解除|相关工具不再提供/);
  result.button.onClick();
  await result.values.pending;
  assert.equal(result.values.request[1].method, "DELETE");
  const running = lifecycle({ ...impact, activeHireCount: 3, activeRunCount: 1, canDelete: false }, { ...resource, kind: "agent" });
  assert.equal(running.button.disabled, true);
  assert.match(running.html, /请先停止未结束的任务，再删除此智能体/);
  assert.doesNotMatch(running.html, /请先解除/);
});

for (const [kind, label] of [["agent", "智能体"], ["skill", "技能"], ["plugin", "插件"], ["workflow", "工作流"], ["knowledge", "知识库"], ["data", "数据源"]]) {
  test(`${label}的删除、恢复和启停使用具体类型，说明符合实际操作`, () => {
    for (const [operation, state, verb] of [["delete", "active", "删除"], ["restore", "deleted", "恢复"], ["status", "active", "停用"], ["status", "disabled", "启用"]]) {
      const result = lifecycle(impact, { ...resource, kind, status: state }, operation);
      assert.equal(result.button.children, `${verb}${label}`);
      assert.match(result.html, new RegExp(`aria-label="${verb}${label}`));
      assert.doesNotMatch(result.html, /资源/);
      if (kind !== "agent") {
        assert.doesNotMatch(result.html, /雇佣|上架/);
      }
      if (kind !== "plugin") {
        assert.doesNotMatch(result.html, /相关工具不再提供/);
      }
    }
  });
}

function picker(query) {
  const values = { buttons: [], query };
  const { VersionPicker } = component("version-picker.tsx", values);
  const html = renderToStaticMarkup(React.createElement(VersionPicker, {
    enterpriseId: "enterprise", kind: "plugin", title: "插件", selected: ["deleted-version"],
    maximum: 20, allowed: true, onChange: (ids) => {
      values.selected = ids;
    },
  }));
  return { values, html };
}

test("成功读取可用版本后保留不可用引用占位，维护者可以移除", () => {
  const result = picker({ data: { items: [] }, loading: false, error: "" });
  assert.match(result.html, /插件 1（不可用）/);
  assert.doesNotMatch(result.html, /deleted-version/);
  result.values.buttons.find((button) => button.children === "移除").onClick();
  assert.equal(result.values.selected.length, 0);
});

test("读取中或读取失败时不把未知状态显示成不可用", () => {
  const loading = picker({ data: null, loading: true, error: "" });
  assert.match(loading.html, /正在读取已选内容/);
  assert.doesNotMatch(loading.html, /不可用/);
  const failed = picker({ data: null, loading: false, error: "读取失败" });
  assert.match(failed.html, /已选插件 1/);
  assert.doesNotMatch(failed.html, /不可用/);
});
