import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {chineseText, loadSource} from "./i18n-test-runtime.mjs";

function loadComponent(file, modules = {}) {
  const exports = {};
  const source = readFileSync(new URL(`../src/features/resource/components/${file}.tsx`, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
  const shared = {
    "react/jsx-runtime": { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: "Fragment" },
    react: { useState: (initial) => [typeof initial === "function" ? initial() : initial, () => {}] },
    "@/lib/i18n/locale-provider": {useT: () => chineseText},
    "@/lib/i18n/translate": loadSource("lib/i18n/translate"),
    "@/features/workflow/components/workflow-development": { workflowInDevelopment: true, WorkflowDevelopmentPage: "WorkflowDevelopmentPage", WorkflowDevelopmentNotice: "WorkflowDevelopmentNotice" },
    "@/features/enterprise/api/organization-api": { organizationPath: (enterprise, path) => `/api/v1/enterprises/${enterprise}${path}` },
  };
  vm.runInNewContext(compiled, { exports, require: (name) => modules[name] ?? shared[name]
    ?? new Proxy({ __esModule: true, default: {} }, { get: (target, key) => key in target ? target[key] : String(key) }) });
  return exports;
}

function nodes(tree) {
  if (!tree || typeof tree !== "object") {
    return [];
  }
  return [tree, ...[tree.props?.children].flat(Infinity).flatMap(nodes)];
}

const { CapabilitiesPage } = loadComponent("capabilities-page", {
  "../lib/resource-display": { resourceTypes: [{ kind: "workflow", path: "workflows", label: "工作流" }, { kind: "agent", path: "agents", label: "智能体" }] },
});

for (const resourceId of [undefined, "new", "existing-workflow"]) {
  test(`工作流${resourceId ?? "列表"}入口只渲染不可用提示`, () => {
    const gate = CapabilitiesPage({ enterpriseId: "enterprise", section: "workflows", resourceId });
    const page = gate.props.children({ user: {}, context: { permissions: ["workflow.view", "workflow.create"], permissionVersion: "1" } });
    const content = nodes(page);
    assert.equal(content.filter((node) => node.type === "WorkflowDevelopmentPage").length, 1);
    assert.equal(content.some((node) => node.type === "ResourceEditor" || node.type === "ResourceList"), false);
  });
}

test("其他能力页面仍使用原来的列表和编辑器", () => {
  for (const resourceId of [undefined, "new", "existing-agent"]) {
    const gate = CapabilitiesPage({ enterpriseId: "enterprise", section: "agents", resourceId });
    const page = gate.props.children({ user: {}, context: { permissions: ["agent.view", "agent.create"], permissionVersion: "1" } });
    assert.equal(nodes(page).some((node) => node.type === (resourceId ? "ResourceEditor" : "ResourceList")), true);
  }
});

test("工作流配置组件在详情、版本等其他入口也不渲染实际编辑器", () => {
  const { ResourceConfigForm } = loadComponent("resource-config-form");
  const page = ResourceConfigForm({ enterpriseId: "enterprise", kind: "workflow", config: { nodes: [{ name: "原有节点" }] }, onChange() {}, permissions: [], errors: {}, readOnly: true });
  assert.equal(page.type, "WorkflowDevelopmentNotice");
});

for (const agentType of ["chat", "workflow"]) {
  test(`${agentType} 智能体隐藏工作流选择并保留已有引用`, () => {
    const queried = [], changed = [];
    const { AgentConfigForm } = loadComponent("agent-config-form", {
      "@/lib/http/use-api-query": { useApiQuery: (path) => {
        queried.push(path);
        return { data: [], loading: false, error: "" };
      } },
    });
    const value = { agentType, modelProfileId: null, skillVersionIds: [], pluginVersionIds: [], knowledgeVersionIds: [], dataVersionIds: [], workflowVersionIds: ["saved-workflow"], entryWorkflowVersionId: "saved-workflow" };
    const props = { enterpriseId: "enterprise", value, onChange: (next) => changed.push(next), permissions: ["agent.run", "skill.use"], errors: {}, section: "capabilities" };
    for (const readOnly of [false, true]) {
      const tree = nodes(AgentConfigForm({ ...props, readOnly }));
      assert.equal(tree.some((node) => node.type === "WorkflowDevelopmentNotice"), true);
      assert.equal(tree.some((node) => node.type === "VersionPicker" && node.props.kind === "workflow"), false);
      assert.equal(tree.some((node) => typeof node.type === "function" && node.type.name === "EntryWorkflow"), false);
    }
    assert.equal(queried.some((path) => path?.includes("kind=workflow")), false);
    const skill = nodes(AgentConfigForm(props)).find((node) => node.type === "VersionPicker" && node.props.kind === "skill");
    skill.props.onChange(["new-skill"]);
    assert.deepEqual(changed[0].workflowVersionIds, value.workflowVersionIds);
    assert.equal(changed[0].entryWorkflowVersionId, value.entryWorkflowVersionId);
  });
}

test("新增智能体不展示不可选择的流程型，已有流程型保留当前值", () => {
  const { AgentConfigForm } = loadComponent("agent-config-form", { "@/lib/http/use-api-query": { useApiQuery: () => ({ data: [], loading: false, error: "" }) } });
  const props = { enterpriseId: "enterprise", value: { agentType: "chat", modelProfileId: null, instructions: "" }, onChange() {}, permissions: [], errors: {}, section: "basic" };
  assert.equal(nodes(AgentConfigForm(props)).some((node) => node.type === "option" && node.props.value === "workflow"), false);
  const tree = nodes(AgentConfigForm({...props, value: {...props.value, agentType: "workflow"}}));
  const option = tree.find((node) => node.type === "option" && node.props.value === "workflow");
  assert.equal(option.props.disabled, true);
  assert.equal(option.props.children, "流程型（当前不可用）");
});

test("没有工作流引用的普通智能体不显示工作流占位区域", () => {
  const { AgentConfigForm } = loadComponent("agent-config-form", { "@/lib/http/use-api-query": { useApiQuery: () => ({ data: [], loading: false, error: "" }) } });
  const tree = nodes(AgentConfigForm({enterpriseId: "enterprise", value: {agentType: "chat", workflowVersionIds: []}, onChange() {}, permissions: [], errors: {}, section: "capabilities"}));
  assert.equal(tree.some((node) => node.type === "WorkflowDevelopmentNotice"), false);
});
