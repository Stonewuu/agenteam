import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {chineseText, localizeCatalog, loadSource} from "./i18n-test-runtime.mjs";

const pasteModule = loadSource("features/file/lib/paste-upload");
const {prepareComposerHandoff} = loadSource("features/agent/lib/composer-handoff");
const source = readFileSync(new URL("../src/features/workspace/components/home-task.tsx", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, {compilerOptions: {
  module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX
}}).outputText;

// 工作台只准备输入并导航，请求由对话页测试验证。
function homeTask(options = {}) {
  const calls = [], states = [], refs = [];
  let cursor = 0, refCursor = 0, routing = false;
  const handoff = {value: null, prepare(input) {
    handoff.value = prepareComposerHandoff(input);
    calls.push({kind: "handoff", value: handoff.value});
    return handoff.value;
  }, navigate(action) {
    action();
    return Promise.resolve();
  }};
  const files = {items: [], fileIds: [], ready: true, clear() {
    files.items = [];
    files.fileIds = [];
    calls.push({kind: "clearFiles"});
  }, add: value => calls.push({kind: "pasteFiles", files: value})};
  const skills = {selected: [], loading: false, error: "", clear() {
    skills.selected = [];
    calls.push({kind: "clearSkills"});
  }};
  const selection = {modelProfileId: "selected-model", reasoningEffort: "high"};
  const model = {ready: true, configurable: true, selection,
    handoff: {configurable: true, selection, defaultSelection: null, models: []}};
  const project = {selected: null, saving: false};
  const react = {
    useRef: current => refs[refCursor++] ??= {current},
    useState(initial) {
      const index = cursor++;
      if (!(index in states)) {
        states[index] = typeof initial === "function" ? initial() : initial;
      }
      return [states[index], value => {
        states[index] = typeof value === "function" ? value(states[index]) : value;
      }];
    },
    useTransition: () => [routing, action => {
      routing = true;
      action();
    }]
  };
  const modules = {
    react,
    "react/jsx-runtime": {jsx: (type, props) => ({type, props}), jsxs: (type, props) => ({type, props}), Fragment: "Fragment"},
    "@/lib/i18n/locale-provider": {useT: () => chineseText},
    "@/lib/i18n/translate": {localizeCatalog},
    "next/link": {__esModule: true, default: "Link"},
    "next/navigation": {useRouter: () => ({push: (path, options) => calls.push({kind: "navigate", path, options})})},
    "@/features/agent/hooks/use-new-conversation-approval-policy": {useNewConversationApprovalPolicy: () => react.useState("default")},
    "@/features/file/hooks/use-file-uploads": {useFileUploads: () => files},
    "@/features/file/lib/paste-upload": pasteModule,
    "@/features/skill/hooks/use-conversation-skills": {useConversationSkills: () => skills},
    "@/features/agent/hooks/use-conversation-model": {useConversationModel: () => model},
    "@/features/project/hooks/use-conversation-project": {useConversationProject: () => project},
    "@/features/agent/hooks/use-composer-selection": {useComposerSelection: () => ({prepare() {}, consume() {}})},
    "@/features/agent/components/composer-handoff-context": {useComposerHandoff: () => handoff},
    "@/features/agent/components/composer-transition": {ComposerTransition: "ComposerTransition", composerSendTransition: "composer-send"},
    "@/features/agent/lib/composer-selection": {composerCommand: () => null},
    "@/features/agent/api/conversation-api": {textInput: text => ({text, links: []})},
    "./navigation-guard": {useBlockNavigation() {}}
  };
  const exports = {};
  vm.runInNewContext(compiled, {exports, URLSearchParams,
    require: name => modules[name] ?? new Proxy({__esModule: true, default: {}}, {
      get: (target, key) => key in target ? target[key] : String(key)
    })});
  const employee = {agentId: "agent-1", name: "测试员工", icon: "Notes", color: "slate", canRun: true, attachmentsEnabled: true};
  function render() {
    cursor = 0;
    refCursor = 0;
    return exports.HomeTask({enterprise: "enterprise-1", userId: "user-1", employees: options.employees ?? [employee],
      permissions: options.permissions ?? ["agent.run", "skill.use", "knowledge.search"]});
  }
  function visit(type, node) {
    if (!node || typeof node !== "object") {
      return null;
    }
    if (node.type === type) {
      return node;
    }
    for (const child of [node.props?.children].flat(Infinity)) {
      const found = visit(type, child);
      if (found) {
        return found;
      }
    }
    return null;
  }
  const find = type => visit(type, render());
  return {calls, handoff, find, employee, model, project, files, skills,
    type: value => find("Textarea").props.onChange({target: {value}, currentTarget: {}, nativeEvent: {}}),
    paste: event => find("Textarea").props.onPaste(event),
    selectPolicy: value => find("ConversationApprovalPolicy").props.onChange(value),
    selectDocuments: value => {
      find("ComposerExtraTools").props.onChooseDocuments({});
      find("DocumentPickerDialog").props.onChange(value);
    },
    submit: () => find("form").props.onSubmit({preventDefault() {}}),
    enter: () => find("Textarea").props.onKeyDown({key: "Enter", nativeEvent: {}, preventDefault() {}})
  };
}

test("粘贴文件会上传，纯文字和不接收附件的员工不会触发上传", () => {
  const home = homeTask();
  const file = new File(["内容"], "附件.txt");
  home.paste({clipboardData: {files: [file], items: []}, preventDefault() {}});
  assert.equal(home.calls[0].files[0], file);
  home.paste({clipboardData: {files: [], items: [{kind: "string"}]}, preventDefault() {}});
  assert.equal(home.calls.length, 1);
  const disabled = homeTask({employees: [{agentId: "no-files", canRun: true, attachmentsEnabled: false}]});
  disabled.paste({clipboardData: {files: [file], items: []}, preventDefault() {}});
  assert.equal(disabled.calls.length, 0);
});

test("没有员工时保留表单布局与广场入口，不允许发送", () => {
  const home = homeTask({employees: [], permissions: ["agent.run", "agent.market_view"]});
  assert.equal(home.find("h2").props.children, "暂未雇佣员工");
  assert.equal(home.find("Link").props.href, "/employees");
  assert.equal(home.find("div").props.children[0].props.inert, true);
  assert.equal(home.find("Textarea").props.disabled, true);
  assert.equal(home.find("EmployeePickerButton").props.name, "暂无员工");
  assert.notEqual(home.find("ConversationModelPlaceholder"), null);
  home.type("不能发送的任务");
  home.submit();
  assert.equal(home.calls.length, 0);
});

test("没有广场权限时不显示入口，已有但不可运行的员工仍保留选择", () => {
  const empty = homeTask({employees: [], permissions: ["agent.run"]});
  assert.equal(empty.find("Link"), null);
  assert.equal(empty.find("p").props.children, "请联系企业管理员获取帮助。");
  const paused = homeTask({employees: [{agentId: "paused", name: "已有员工", canRun: false}]});
  assert.equal(paused.find("h2"), null);
  assert.notEqual(paused.find("EmployeePickerButton"), null);
});

for (const policy of ["default", "auto_approve", "full_access"]) {
  test(`点击发送立即进入对话页，传递 ${policy} 和当前员工、模型选项`, () => {
    const home = homeTask();
    home.selectPolicy(policy);
    home.type("  检查项目文件  ");
    home.submit();
    const handed = home.handoff.value;
    assert.equal(handed.input.text, "检查项目文件");
    assert.equal(handed.employee, home.employee);
    assert.equal(handed.approvalPolicy, policy);
    assert.equal(handed.input.modelSelection, home.model.selection);
    assert.equal(handed.modelOptions, home.model.handoff);
    assert.equal(handed.conversationId, null);
    const navigation = home.calls.find(call => call.kind === "navigate");
    const url = new URL(navigation.path, "https://localhost");
    assert.equal(url.pathname, "/new-task");
    assert.equal(url.searchParams.get("agent"), home.employee.agentId);
    assert.equal(url.searchParams.get("submission"), handed.submission.id);
    assert.equal(navigation.options.transitionTypes[0], "composer-send");
    assert.equal(home.find("Textarea").props.value, "");
  });
}

test("模型选项未就绪时保留输入，不提前跳转", () => {
  const home = homeTask();
  home.model.ready = false;
  home.type("等待模型选项");
  home.submit();
  assert.equal(home.calls.length, 0);
  assert.equal(home.find("Textarea").props.value, "等待模型选项");
});

test("附件、技能、资料和项目在清理工作台前完整传递给对话页，仅附件也可以提交", () => {
  const home = homeTask();
  const file = {id: "file", name: "附件.txt", status: "ready"};
  home.files.items = [{reference: file}];
  home.files.fileIds = [file.id];
  home.skills.selected = [{versionId: "skill", name: "整理文件"}];
  home.project.selected = {id: "project", name: "共享项目"};
  home.selectDocuments([{documentId: "document", generation: 2, name: "资料"}]);
  home.submit();
  const handed = home.handoff.value;
  assert.equal(handed.input.text, "");
  assert.equal(handed.submission.input.attachments[0], file);
  assert.equal(handed.input.attachmentIds[0], file.id);
  assert.equal(handed.input.skillVersionIds[0], "skill");
  assert.equal(handed.input.knowledgeReferences[0].generation, 2);
  assert.equal(handed.project.id, "project");
  assert.equal(home.files.items.length, 0);
  assert.equal(home.calls[0].kind, "handoff");
});

test("同一表单事件重复触发也只交接和导航一次，回车发送保留焦点意图", () => {
  const home = homeTask();
  home.type("重复发送");
  const submit = home.find("form").props.onSubmit;
  submit({preventDefault() {}});
  submit({preventDefault() {}});
  assert.equal(home.calls.filter(call => call.kind === "handoff").length, 1);
  assert.equal(home.calls.filter(call => call.kind === "navigate").length, 1);
  assert.equal(home.handoff.value.focusInput, false);
  const keyboard = homeTask();
  keyboard.type("回车发送");
  keyboard.enter();
  assert.equal(keyboard.handoff.value.focusInput, true);
});
