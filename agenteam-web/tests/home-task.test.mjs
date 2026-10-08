import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";
import { chineseText, localizeCatalog, loadSource } from "./i18n-test-runtime.mjs";

const pasteModule = loadSource("features/file/lib/paste-upload");

const source = readFileSync(new URL("../src/features/workspace/components/home-task.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;

// 调用实际首页表单事件，只替换路由、请求与组件外观，不向真实模型发送测试消息。
function homeTask(request, options = {}) {
  const calls = [], states = [];
  let cursor = 0, routing = false, pending;
  const action = { busy: false, error: "", mutation: { run: async (path, options) => {
 calls.push({ kind: "request", path, options }); return request(); 
} },
    execute(operation) {
 action.busy = true; pending = operation().catch((error) => {
 action.error = error.message; 
}).finally(() => {
 action.busy = false; 
}); return pending; 
} };
  const files = { items: [], fileIds: [], ready: true, clear: () => calls.push({ kind: "clearFiles" }), add: value => calls.push({ kind: "pasteFiles", files: value }) };
  const skills = { selected: [], loading: false, error: "", clear: () => calls.push({ kind: "clearSkills" }) };
  const selection = { modelProfileId: "selected-model", reasoningEffort: "high" };
  const model = { ready: true, configurable: true, selection, handoff: { configurable: true, selection, defaultSelection: null, models: [] } };
  const project = { selected: null, saving: false };
  const react = {
    useRef: (current) => ({ current }),
    useState(initial) {
 const index = cursor++; if (!(index in states)) {
states[index] = typeof initial === "function" ? initial() : initial;
} return [states[index], (value) => {
 states[index] = typeof value === "function" ? value(states[index]) : value; 
}]; 
},
    useTransition: () => [routing, (callback) => {
 routing = true; callback(); 
}],
  };
  const modules = {
    "@/lib/i18n/locale-provider": { useT: () => chineseText },
    "@/lib/i18n/translate": { localizeCatalog },
    "@/features/agent/hooks/use-new-conversation-approval-policy": { useNewConversationApprovalPolicy: () => react.useState(options.policy ?? "default") },
    react,
    "react/jsx-runtime": { jsx: (type, props) => ({ type, props }), jsxs: (type, props) => ({ type, props }), Fragment: "Fragment" },
    "next/link": { __esModule: true, default: "Link" },
    "next/navigation": { useRouter: () => ({ push: (path, options) => calls.push({ kind: "navigate", path, options }) }) },
    "@/features/auth/hooks/use-form-action": { useFormAction: () => action },
    "@/features/file/hooks/use-file-uploads": { useFileUploads: () => files },
    "@/features/file/lib/paste-upload": pasteModule,
    "@/features/skill/hooks/use-conversation-skills": { useConversationSkills: () => skills },
    "@/features/agent/hooks/use-conversation-model": { useConversationModel: () => model },
    "@/features/project/hooks/use-conversation-project": { useConversationProject: () => project },
    "@/features/agent/hooks/use-composer-selection": { useComposerSelection: () => ({}) },
    "@/features/agent/components/conversation-navigation-context": { useConversationNavigation: () => ({ list: { refresh() {} } }) },
    "@/features/agent/components/composer-handoff-context": { useComposerHandoff: () => ({ prepare: (value) => calls.push({ kind: "handoff", value }) }) },
    "@/features/agent/components/composer-transition": { ComposerTransition: "ComposerTransition", composerSendTransition: "composer-send" },
    "@/features/agent/lib/composer-selection": { composerCommand: () => null },
    "@/features/agent/api/conversation-api": { conversationPath: () => "/conversations", textInput: (text) => ({ text }) },
    "./navigation-guard": { useBlockNavigation() {} },
  };
  const exports = {};
  vm.runInNewContext(compiled, { exports, require: (name) => modules[name] ?? new Proxy({ __esModule: true, default: {} }, { get: (target, key) => key in target ? target[key] : String(key) }) });
  const employee = { agentId: "agent-1", name: "测试员工", icon: "Notes", color: "slate", canRun: true, attachmentsEnabled: true };
  const render = () => {
    cursor = 0;
    return exports.HomeTask({ enterprise: "enterprise-1", userId: "user-1", employees: options.employees ?? [employee],
      permissions: options.permissions ?? ["agent.run", "skill.use", "knowledge.search"], onChanged() {} });
  };
  function visit(type, node) {
    if (!node || typeof node !== "object") {
return null;
}
    if (node.type === type) {
return node;
}
    for (const child of [node.props?.children].flat(Infinity)) {
 const found = visit(type, child); if (found) {
return found;
} 
}
    return null;
  }
  const find = (type) => visit(type, render());
  return { calls, action, find, employee, model, project,
    selectPolicy: (policy) => find("ConversationApprovalPolicy").props.onChange(policy),
    type: (value) => find("Textarea").props.onChange({ target: { value }, currentTarget: {}, nativeEvent: {} }),
    paste: event => find("Textarea").props.onPaste(event),
    submit: () => {
 find("form").props.onSubmit({ preventDefault() {} }); return pending; 
},
    submitWithEnter: () => {
 find("Textarea").props.onKeyDown({ key: "Enter", nativeEvent: {}, preventDefault() {} }); return pending; 
},
  };
}

test("工作台直接粘贴文件会上传，纯文字粘贴与附件禁用不会触发上传", () => {
  const page = homeTask(async () => ({}));
  const file = new File(["内容"], "附件.txt");
  let prevented = 0;
  page.paste({ clipboardData: { files: [file], items: [] }, preventDefault() {
 prevented += 1; 
} });
  assert.equal(prevented, 1);
  assert.deepEqual(page.calls.filter(call => call.kind === "pasteFiles"), [{ kind: "pasteFiles", files: [file] }]);
  page.paste({ clipboardData: { files: [], items: [{ kind: "string" }] }, preventDefault() {
 prevented += 1; 
} });
  assert.equal(prevented, 1);
  const disabled = homeTask(async () => ({}), { employees: [{ agentId: "no-files", name: "仅文字", canRun: true, attachmentsEnabled: false }] });
  disabled.paste({ clipboardData: { files: [file], items: [] }, preventDefault() {
 prevented += 1; 
} });
  assert.equal(prevented, 1);
  assert.deepEqual(disabled.calls.filter(call => call.kind === "pasteFiles"), []);
});

test("没有员工时显示员工广场入口，收起不可用的任务输入和工具", () => {
  const home = homeTask(async () => ({}), { employees: [], permissions: ["agent.run", "agent.market_view"] });
  assert.equal(home.find("h2").props.children, "找一位数字员工，开始协作");
  assert.equal(home.find("Link").props.href, "/enterprises/enterprise-1/employees");
  assert.equal(home.find("form"), null);
  assert.equal(home.find("Textarea"), null);
  assert.equal(home.find("EmployeePickerButton"), null);
  assert.equal(home.find("ConversationModelControls"), null);
  assert.equal(home.find("ConversationApprovalPolicy"), null);
  assert.equal(home.calls.length, 0);
});

test("没有员工广场权限时不显示无法访问的入口", () => {
  const home = homeTask(async () => ({}), { employees: [], permissions: ["agent.run"] });
  assert.equal(home.find("h2").props.children, "暂无可用的数字员工");
  assert.equal(home.find("Link"), null);
  assert.equal(home.find("form"), null);
});

test("已有员工但暂时不可运行时保留员工选择，不显示无员工引导", () => {
  const home = homeTask(async () => ({}), { employees: [{ agentId: "paused", name: "已有员工", canRun: false }] });
  assert.notEqual(home.find("form"), null);
  assert.notEqual(home.find("EmployeePickerButton"), null);
  assert.equal(home.find("h2"), null);
});

for (const policy of ["default", "auto_approve", "full_access"]) {
test(`首页发送保存 ${policy}，过渡期间沿用同一员工和策略`, async () => {
  const home = homeTask(async () => ({ conversationId: "conversation-1" }));
  home.selectPolicy(policy); home.type("  测试消息  ");
  await home.submit();
  const request = home.calls.find((call) => call.kind === "request");
  assert.equal(request.options.body.approvalPolicy, policy);
  assert.equal(request.options.body.agentId, home.employee.agentId);
  assert.equal(request.options.body.input.text, "测试消息");
  assert.equal(request.options.body.input.modelSelection, home.model.selection);
  const handoff = home.calls.find((call) => call.kind === "handoff").value;
  assert.equal(handoff.approvalPolicy, policy);
  assert.equal(handoff.employee, home.employee);
  assert.equal(handoff.conversationId, "conversation-1");
  assert.equal(handoff.modelOptions, home.model.handoff);
  const navigation = home.calls.find((call) => call.kind === "navigate");
  assert.equal(navigation.path, "/enterprises/enterprise-1/conversations/conversation-1");
  assert.equal(navigation.options.transitionTypes.join(","), "composer-send");
  assert.equal(home.find("Textarea").props.value, "");
});
}

test("模型选项未加载完成时保留草稿，不发送缺少选择的任务", async () => {
  const home = homeTask(async () => ({ conversationId: "conversation-1" }));
  home.model.ready = false;
  home.type("等模型加载后发送");
  await home.submit();
  assert.equal(home.calls.length, 0);
  assert.equal(home.find("Textarea").props.value, "等模型加载后发送");
});

test("所选项目随首次发送保存，未选择时交给服务端创建", async () => {
  const chosen = { id: "project-1", name: "季度报告", directory: "projects/reports" };
  const shared = homeTask(async () => ({ conversationId: "shared" }));
  shared.project.selected = chosen;
  shared.type("继续整理报告");
  await shared.submit();
  assert.equal(shared.calls.find((call) => call.kind === "request").options.body.projectId, chosen.id);
  assert.equal(shared.calls.find((call) => call.kind === "handoff").value.project, chosen);
  const automatic = homeTask(async () => ({ conversationId: "automatic" }));
  automatic.type("独立任务");
  await automatic.submit();
  assert.equal(automatic.calls.find((call) => call.kind === "request").options.body.projectId, null);
});

test("首次发送失败仍保留项目选择和草稿", async () => {
  const home = homeTask(async () => {
    throw new Error("本次请求失败");
  });
  const project = { id: "project-1", name: "共享报告", directory: "projects/shared" };
  home.project.selected = project;
  home.type("继续整理");
  await home.submit();
  assert.equal(home.project.selected, project);
  assert.equal(home.find("Textarea").props.value, "继续整理");
  assert.equal(home.calls.some((call) => call.kind === "navigate"), false);
});

test("创建失败不清空草稿和策略，也不开始页面过渡", async () => {
  const home = homeTask(async () => {
 throw new Error("本次请求失败"); 
});
  home.selectPolicy("auto_approve"); home.type("保留我的任务");
  await home.submit();
  assert.equal(home.find("Textarea").props.value, "保留我的任务");
  assert.equal(home.find("ConversationApprovalPolicy").props.value, "auto_approve");
  assert.deepEqual(home.calls.map((call) => call.kind), ["request"]);
  assert.equal(home.find("Textarea").props.disabled, false);
});

test("请求未返回时不提前清空或导航，导航等待期间继续禁用表单", async () => {
  let accept;
  const home = homeTask(() => new Promise((resolve) => {
 accept = resolve; 
}));
  home.type("等待发送的任务");
  const pending = home.submit();
  assert.equal(home.find("Textarea").props.value, "等待发送的任务");
  assert.equal(home.find("Textarea").props.disabled, true);
  assert.equal(home.find("ConversationApprovalPolicy").props.disabled, true);
  assert.deepEqual(home.calls.map((call) => call.kind), ["request"]);
  accept({ conversationId: "conversation-2" });
  await pending;
  assert.equal(home.find("Textarea").props.disabled, true);
  assert.equal(home.calls.filter((call) => call.kind === "navigate").length, 1);
});

test("按回车发送保留继续输入的意图，点击发送不强制请求键盘焦点", async () => {
  const keyboard = homeTask(async () => ({ conversationId: "keyboard" }));
  keyboard.type("键盘发送"); await keyboard.submitWithEnter();
  assert.equal(keyboard.calls.find((call) => call.kind === "handoff").value.focusInput, true);
  const pointer = homeTask(async () => ({ conversationId: "pointer" }));
  pointer.type("点击发送"); await pointer.submit();
  assert.equal(pointer.calls.find((call) => call.kind === "handoff").value.focusInput, false);
});
