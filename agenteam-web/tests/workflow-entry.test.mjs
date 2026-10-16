import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {chineseText, loadSource} from "./i18n-test-runtime.mjs";

const compiledComponents = new Map();
function loadComponent(file, modules = {}) {
  const exports = {};
  if (!compiledComponents.has(file)) {
    const source = readFileSync(new URL(`../src/features/resource/components/${file}.tsx`, import.meta.url), "utf8");
    compiledComponents.set(file, ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText);
  }
  const compiled = compiledComponents.get(file);
  const shared = {
    "react/jsx-runtime": { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: "Fragment" },
    react: { useState: (initial) => [typeof initial === "function" ? initial() : initial, () => {}] },
    "@/lib/i18n/locale-provider": {useT: () => chineseText},
    "@/lib/i18n/translate": loadSource("lib/i18n/translate"),
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
  test(`工作流${resourceId ?? "列表"}入口开放实际编辑器与列表`, () => {
    const gate = CapabilitiesPage({ enterpriseId: "enterprise", section: "workflows", resourceId });
    const page = gate.props.children({ user: {}, context: { permissions: ["workflow.view", "workflow.create"], permissionVersion: "1" } });
    const content = nodes(page);
    assert.equal(content.some((node) => node.type === (resourceId ? "ResourceEditor" : "ResourceList")), true);
    assert.equal(page.props.fullHeight, Boolean(resourceId));
  });
}

test("其他能力页面仍使用原来的列表和编辑器", () => {
  for (const resourceId of [undefined, "new", "existing-agent"]) {
    const gate = CapabilitiesPage({ enterpriseId: "enterprise", section: "agents", resourceId });
    const page = gate.props.children({ user: {}, context: { permissions: ["agent.view", "agent.create"], permissionVersion: "1" } });
    assert.equal(nodes(page).some((node) => node.type === (resourceId ? "ResourceEditor" : "ResourceList")), true);
    assert.equal(page.props.fullHeight, false);
  }
});

test("工作流配置在详情与历史版本中渲染实际编辑器", () => {
  const { ResourceConfigForm } = loadComponent("resource-config-form");
  const page = ResourceConfigForm({ enterpriseId: "enterprise", kind: "workflow", config: { nodes: [{ name: "原有节点" }] }, onChange() {}, permissions: [], errors: {}, readOnly: true });
  assert.equal(page.type, "WorkflowEditor");
  assert.equal(page.props.fillHeight, undefined);
});

for (const agentType of ["chat", "workflow"]) {
  test(`${agentType} 智能体开放工作流选择并保留已有引用`, () => {
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
      assert.equal(tree.some((node) => node.type === "VersionPicker" && node.props.kind === "workflow"), true);
      assert.equal(tree.some((node) => typeof node.type === "function" && node.type.name === "EntryWorkflow"), agentType === "workflow");
    }
    assert.equal(queried.some((path) => path?.includes("kind=workflow")), false);
    const skill = nodes(AgentConfigForm(props)).find((node) => node.type === "VersionPicker" && node.props.kind === "skill");
    skill.props.onChange(["new-skill"]);
    assert.deepEqual(changed[0].workflowVersionIds, value.workflowVersionIds);
    assert.equal(changed[0].entryWorkflowVersionId, value.entryWorkflowVersionId);
  });
}

test("流程型智能体可以选择，已有流程型保留当前值", () => {
  const { AgentConfigForm } = loadComponent("agent-config-form", { "@/lib/http/use-api-query": { useApiQuery: () => ({ data: [], loading: false, error: "" }) } });
  const props = { enterpriseId: "enterprise", value: { agentType: "chat", modelProfileId: null, instructions: "" }, onChange() {}, permissions: [], errors: {}, section: "basic" };
  assert.equal(nodes(AgentConfigForm(props)).some((node) => node.type === "option" && node.props.value === "workflow"), true);
  const tree = nodes(AgentConfigForm({...props, value: {...props.value, agentType: "workflow"}}));
  const option = tree.find((node) => node.type === "option" && node.props.value === "workflow");
  assert.equal(Boolean(option.props.disabled), false);
  assert.equal(option.props.children, "流程型");
});
