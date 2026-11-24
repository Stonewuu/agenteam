import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {chineseText, loadSource} from "./i18n-test-runtime.mjs";

const submissions = loadSource("features/agent/lib/conversation-submissions");
const {prepareComposerHandoff} = loadSource("features/agent/lib/composer-handoff");
const {toAssistantMessage} = loadSource("features/agent/lib/conversation-ui-adapter");
const {createConversationSubmission, mergeConversationSubmissions, observeConversationSubmissions} = submissions;
const scope = "user:enterprise:conversation";
const accepted = {conversationId: "conversation", runId: "run", inputMessageId: "input", outputMessageId: "output", status: "queued"};
const attachment = {id: "file", name: "附件.txt", status: "ready", mediaType: "text/plain", sizeBytes: 12};

function sending(options = {}) {
  return createConversationSubmission({scope, text: "列出文件", attachments: [attachment], labels: ["文件技能"],
    messages: [], queued: false, ...options});
}

test("发送消息立即显示文字、附件与等待动画，确认前后保持相同显示键", () => {
  const submission = sending();
  const initial = mergeConversationSubmissions([], [submission], scope);
  assert.equal(initial.length, 2);
  assert.equal(initial[0].localDelivery, "sending");
  assert.equal(initial[0].attachments[0].name, "附件.txt");
  assert.equal(initial[0].blocks[0].label, "文件技能");
  const confirmed = {...submission, accepted};
  const waiting = mergeConversationSubmissions([], [confirmed], scope);
  assert.equal(waiting[0].id, "input");
  assert.equal(waiting[1].id, "output");
  assert.equal(waiting[1].localDelivery, "accepted");
  const saved = [{...waiting[0], localDelivery: undefined}, {...waiting[1], localDelivery: undefined, content: "文件如下", status: "streaming"}];
  const merged = mergeConversationSubmissions(saved, [confirmed], scope);
  assert.equal(merged.length, 2);
  assert.equal(merged[1].content, "文件如下");
  assert.equal(merged[1].localDelivery, undefined);
  assert.deepEqual(initial.map(value => toAssistantMessage(value).id), merged.map(value => toAssistantMessage(value).id));
  const observed = observeConversationSubmissions([confirmed], scope, saved);
  assert.deepEqual(mergeConversationSubmissions([], observed, scope), []);
  assert.deepEqual(mergeConversationSubmissions([], [confirmed], "other"), []);
});

test("事件先于接口响应时不重复显示，不把之前的相同消息或不同附件当成本次发送", () => {
  const submission = sending({messages: [{id: "old"}]});
  const saved = {...submission.input, id: "input", runId: "run", localDelivery: undefined};
  const old = {...saved, id: "old"};
  const reply = {...submission.reply, id: "output", runId: "run", localDelivery: undefined};
  const merged = mergeConversationSubmissions([old, saved, reply], [submission], scope);
  assert.equal(merged.length, 3);
  assert.equal(merged[0].clientId, undefined);
  assert.equal(merged[1].clientId, submission.input.id);
  assert.equal(merged[2].clientId, submission.reply.id);
  assert.equal(mergeConversationSubmissions([old], [submission], scope).length, 3);
  assert.equal(mergeConversationSubmissions([{...saved, attachments: []}], [submission], scope).length, 3);
  const confirmed = {...submission, accepted};
  assert.equal(mergeConversationSubmissions([{...saved, id: "different"}], [confirmed], scope).length, 3);
});

test("补充消息保持排队语义，只补充缺少的本轮消息，不伪造正在回复的状态", () => {
  const submission = sending({queued: true});
  assert.equal(mergeConversationSubmissions([], [submission], scope).length, 1);
  const confirmed = {...submission, accepted: {...accepted, status: "waiting_turn"}};
  assert.equal(mergeConversationSubmissions([], [confirmed], scope).length, 1);
  const started = {...submission.reply, id: "output", runId: "run", localDelivery: undefined, status: "streaming"};
  const merged = mergeConversationSubmissions([started], [confirmed], scope);
  assert.deepEqual(merged.map(message => message.role), ["user", "assistant"]);
});

const hookSource = readFileSync(new URL("../src/features/agent/hooks/use-conversation-workspace.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(hookSource, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022}}).outputText;

// 使用实际发送入口，替换网络和页面上下文，控制接口响应顺序。
function workspace({queued = false, fresh = false, handoff = null, search = "agent=agent", deferHistory = false} = {}) {
  const slots = [];
  const effects = [];
  const requests = [];
  let cursor = 0;
  let changed = false;
  let complete;
  let fail;
  let path = fresh ? "/new-task" : "/conversations/conversation";
  let pendingPath = null;
  let locationPathname = path;
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) {
        slots[index] = typeof initial === "function" ? initial() : initial;
      }
      return [slots[index], value => {
        const next = typeof value === "function" ? value(slots[index]) : value;
        changed ||= next !== slots[index];
        slots[index] = next;
      }];
    },
    useRef(value) {
      const index = cursor++;
      return slots[index] ??= {current: value};
    },
    useMemo: operation => operation(), useCallback: operation => operation, useEffectEvent: operation => operation, useId: () => "scope",
    useEffect: operation => effects.push(operation)
  };
  const files = {items: [{reference: attachment}], fileIds: [attachment.id], ready: true, clear() {
    files.items = [];
    files.fileIds = [];
  }};
  const skills = {selected: [{name: "文件技能", versionId: "skill"}], loading: false, error: "", clear() {
    skills.selected = [];
  }};
  const employee = {agentId: "agent", name: "工作助手", icon: "Notes", color: "orange", canRun: true, attachmentsEnabled: true};
  const stream = {state: fresh ? null : {snapshot: {
    conversation: {id: "conversation", agentId: "agent", mode: "normal", status: "active", canContinue: true},
    messages: [], attachmentsEnabled: true, activeRun: queued ? {id: "previous", mode: "interactive", status: "running"} : null
  }, latestRun: null}, reload() {}, updateMetadata() {}};
  const modelHandoff = {configurable: true, selection: {modelProfileId: "model-1", reasoningEffort: "high"}, defaultSelection: null, models: []};
  const selectedProject = {id: "project-1", name: "测试项目"};
  const policy = {ready: true, saving: false, value: "auto_approve", resetDraft() {}};
  let modelLoaded = true;
  const modules = {
    react, "next/navigation": {usePathname: () => path, useSearchParams: () => new URLSearchParams(search)},
    "@/lib/i18n/locale-provider": {useT: () => chineseText},
    "@/features/auth/lib/workspace-path": {workspaceParts: value => value.split("/").filter(Boolean)},
    "@/lib/http/api-client": {ApiMutation: class {
      run(route, options) {
        requests.push({route, options});
        return new Promise((resolve, reject) => {
          complete = resolve;
          fail = reject;
        });
      }
    }, errorMessage: error => error.message},
    "@/lib/http/use-api-query": {useApiQuery: route => ({data: route?.endsWith("/home") ? {employees: [employee]} : employee})},
    "@/features/enterprise/api/organization-api": {organizationPath: (_, suffix) => suffix},
    "../api/conversation-api": {conversationPath: (_, id) => id ? `/conversations/${id}` : "/conversations", textInput: text => ({text})},
    "../lib/conversation-display": loadSource("features/agent/lib/conversation-display"),
    "../lib/conversation-submissions": submissions,
    "./use-conversation-stream": {useConversationStream: () => stream},
    "./use-conversation-list": {useConversationList: () => ({items: [], refresh() {}, updateMetadata() {}})},
    "./use-conversation-scroll": {useConversationScroll: () => ({shouldStickToBottomRef: {current: true}, scrollToBottom() {}})},
    "../lib/conversation-reading": {conversationReadingPosition: () => null},
    "@/features/file/hooks/use-file-uploads": {useFileUploads: () => files},
    "@/features/skill/hooks/use-conversation-skills": {useConversationSkills: () => skills},
    "./use-conversation-approval-policy": {useConversationApprovalPolicy: ({conversationId, conversation, initialPolicy}) => ({
      ...policy,
      value: conversationId ? (conversation?.approvalPolicy ?? initialPolicy ?? "default") : policy.value
    })},
    "./use-conversation-model": {useConversationModel: ({conversation, initialOptions}) => ({
      ...policy,
      configurable: false,
      loaded: modelLoaded,
      handoff: conversation ? undefined : modelHandoff,
      initialOptions
    })},
    "@/features/project/hooks/use-conversation-project": {useConversationProject: ({conversationId, conversation, initialProject}) => ({
      ...policy,
      selected: conversationId ? (conversation ? selectedProject : initialProject ?? null) : selectedProject,
      initialProject
    })},
    "../components/composer-handoff-context": {useComposerHandoff: () => handoff}
  };
  const exports = {};
  const applyHistory = next => {
    const split = next.indexOf("?");
    const nextPathname = split < 0 ? next : next.slice(0, split);
    const nextSearch = split < 0 ? "" : next.slice(split + 1);
    locationPathname = nextPathname;
    if (deferHistory) {
      pendingPath = {pathname: nextPathname, search: nextSearch};
    } else {
      path = nextPathname;
      if (split >= 0) {
        search = nextSearch;
      }
    }
  };
  vm.runInNewContext(compiled, {exports, crypto, URLSearchParams, window: {
    location: {get pathname() {
      return locationPathname;
    }},
    addEventListener() {}, removeEventListener() {}, history: {pushState(_, __, next) {
      applyHistory(next);
    }, replaceState(_, __, next) {
      applyHistory(next);
    }}
  }, require: name => modules[name] ?? new Proxy({}, {get: () => () => ({})})});
  function render() {
    let view;
    for (let attempts = 0; attempts < 5; attempts++) {
      cursor = 0;
      changed = false;
      effects.length = 0;
      view = exports.useConversationWorkspace("user", "enterprise", true, true, true);
      if (!changed) {
        effects.forEach(effect => effect());
        return view;
      }
    }
    throw new Error("对话状态未能稳定");
  }
  return {render, requests, files, skills, stream, path: () => path, locationPathname: () => locationPathname,
    setModelLoaded: value => {
      modelLoaded = value;
    },
    commitHistory() {
      if (pendingPath) {
        path = pendingPath.pathname;
        search = pendingPath.search;
        pendingPath = null;
      }
    },
    resolve: value => complete(value), reject: error => fail(error), navigate: value => {
      const split = value.indexOf("?");
      path = split < 0 ? value : value.slice(0, split);
      if (split >= 0) {
        search = value.slice(split + 1);
      }
      locationPathname = path;
      pendingPath = null;
    }};
}

test("接口延迟时输入框和选择立即清空，重复点击只发送一次，失败完整恢复后可以重试", async () => {
  const page = workspace();
  page.render().setDraft("  列出文件\n");
  page.render().chooseDocuments([{documentId: "document", name: "资料", generation: 1}]);
  const view = page.render();
  const pending = view.submitMessageContent();
  await view.submitMessageContent();
  const waiting = page.render();
  assert.equal(waiting.draft, "");
  assert.equal(waiting.sending, true);
  assert.equal(waiting.files.items.length, 0);
  assert.equal(waiting.messages[0].content, "列出文件");
  assert.equal(waiting.messages[0].attachments.length, 1);
  assert.equal(page.requests.length, 1);
  assert.equal(page.requests[0].options.body.knowledgeReferences[0].documentId, "document");
  page.reject(new Error("消息未能发送，请重试。"));
  await pending;
  const failed = page.render();
  assert.equal(failed.draft, "  列出文件\n");
  assert.equal(failed.messages.length, 0);
  assert.equal(failed.files.items.length, 1);
  assert.equal(failed.skills.selected.length, 1);
  assert.equal(failed.selectedDocuments.length, 1);
  assert.equal(failed.actionError, "消息未能发送，请重试。");
  const retry = failed.submitMessageContent();
  page.resolve(accepted);
  await retry;
  assert.equal(page.render().messages.length, 2);
  assert.equal(page.render().draft, "");
  assert.equal(page.render().files.items.length, 0);
  assert.equal(page.render().sending, false);
});

test("新对话创建后在快照读取完成前保持消息，切换对话后失败不覆盖当前草稿", async () => {
  const fresh = workspace({fresh: true});
  fresh.render().setDraft("新对话");
  const pending = fresh.render().submitMessageContent();
  assert.equal(fresh.render().messages.length, 2);
  fresh.resolve(accepted);
  await pending;
  assert.equal(fresh.render().conversationId, "conversation");
  assert.equal(fresh.render().messages[0].content, "新对话");

  const page = workspace({queued: true});
  page.render().setDraft("补充内容");
  const queued = page.render().submitMessageContent();
  assert.equal(page.render().messages.length, 1);
  page.navigate("/conversations/another");
  page.render().setDraft("另一段草稿");
  page.render();
  page.reject(new Error("发送失败"));
  await queued;
  assert.equal(page.render().draft, "另一段草稿");
  assert.equal(page.render().actionError, "");
  page.navigate("/conversations/conversation");
  assert.equal(page.render().draft, "补充内容");
});

test("工作台交接的已提交消息在对话加载期间保留，交接结束和服务器消息到达不会重复显示", () => {
  const submission = {...sending(), accepted};
  const handoff = {value: {conversationId: "conversation", userId: "user", enterpriseId: "enterprise", status: "accepted", submission, focusInput: true,
    employee: {agentId: "agent", name: "工作助手", icon: "Notes", color: "orange"}}, finish() {
      handoff.value = null;
    }};
  const page = workspace({handoff});
  const savedState = page.stream.state;
  page.stream.state = null;
  const waiting = page.render();
  assert.equal(waiting.messages.length, 2);
  assert.equal(waiting.messages[0].id, "input");
  assert.equal(waiting.messages[1].id, "output");
  assert.equal(waiting.messages[1].localDelivery, "accepted");
  assert.equal(waiting.composerHandoff, true);
  assert.equal(waiting.focusComposerAfterSend, true);
  assert.equal(waiting.canSubmit, false);
  page.stream.error = "对话读取超时";
  const delayed = page.render();
  assert.notEqual(handoff.value, null);
  assert.equal(delayed.agentName, "工作助手");
  assert.equal(delayed.messages[0].id, "input");
  page.stream.error = "";
  page.stream.state = {...savedState, snapshot: {...savedState.snapshot, messages: waiting.messages.map(message => ({
    ...message, blocks: [], localDelivery: undefined, status: "completed",
    content: message.role === "assistant" ? "已处理" : message.content
  }))}};
  const received = page.render();
  assert.equal(handoff.value, null);
  assert.equal(received.messages.length, 2);
  assert.equal(received.messages[1].content, "已处理");
  assert.equal(received.messages[1].clientId, waiting.messages[1].clientId);
  assert.equal(page.render().messages[1].localDelivery, undefined);
  const otherScope = workspace({handoff: {value: {conversationId: "conversation",
    employee: {agentId: "agent"}, submission: {...submission, scope: "another-user:enterprise:conversation"}}}});
  assert.equal(otherScope.render().messages.length, 0);
});

test("对话页复用发送入口创建任务，失败原位重试并保留附件，成功后更新地址", async () => {
  const initial = prepareComposerHandoff({userId: "user", enterpriseId: "enterprise", employee: {agentId: "agent", name: "工作助手"},
    approvalPolicy: "auto_approve", focusInput: true, project: {id: "project"},
    input: {text: "检查项目文件", attachmentIds: [attachment.id], skillVersionIds: ["skill"],
      knowledgeReferences: [{documentId: "document", generation: 1}], links: []}, attachments: [attachment], labels: ["技能"]});
  const submission = initial.submission;
  const handoff = {value: initial, update(next, error = "") {
      handoff.value = {...handoff.value, submission: next, error, conversationId: next.accepted?.conversationId ?? null,
        status: next.failed ? "failed" : next.accepted ? "accepted" : "sending"};
    }, finish() {
      handoff.value = null;
    }};
  const page = workspace({fresh: true, handoff, search: `agent=agent&submission=${submission.id}`});
  const pending = page.render();
  assert.equal(pending.messages.length, 2);
  assert.equal(pending.messages[0].content, submission.input.content);
  assert.equal(pending.canSubmit, false);
  assert.equal(pending.submitting, true);
  page.render();
  page.render();
  assert.equal(page.requests.length, 1);
  assert.equal(page.requests[0].route, "/conversations");
  assert.equal(page.requests[0].options.body.input, initial.input);
  assert.equal(page.requests[0].options.body.approvalPolicy, "auto_approve");
  assert.equal(page.requests[0].options.body.projectId, "project");
  page.reject(new Error("发送失败"));
  await new Promise(resolve => setImmediate(resolve));
  const failed = page.render();
  assert.equal(failed.messages.length, 1);
  assert.equal(failed.creationError, "发送失败");
  assert.equal(failed.messages[0].localDelivery, "failed");
  failed.retryCreation();
  failed.retryCreation();
  assert.equal(page.requests.length, 2);
  assert.equal(page.requests[1].options.body.input, initial.input);
  assert.equal(page.render().messages.length, 2);
  page.resolve(accepted);
  await new Promise(resolve => setImmediate(resolve));
  const confirmed = page.render();
  assert.equal(confirmed.conversationId, "conversation");
  assert.equal(page.path(), "/conversations/conversation");
  assert.equal(page.render().messages.length, 2);
  assert.equal(confirmed.messages[0].clientId, pending.messages[0].clientId);
  assert.equal(confirmed.messages[0].id, "input");
  assert.equal(page.requests.length, 2);
});

test("创建接口早于对话页打开时也直接显示已提交消息，并替换为服务器对话地址", () => {
  const submission = {...sending(), accepted};
  const handoff = {value: {userId: "user", enterpriseId: "enterprise", conversationId: "conversation", status: "accepted",
    submission, employee: {agentId: "agent", name: "工作助手"}, focusInput: false}};
  const page = workspace({fresh: true, handoff, search: `agent=agent&submission=${submission.id}`});
  assert.equal(page.render().messages.length, 2);
  assert.equal(page.path(), "/conversations/conversation");
  assert.equal(page.render().messages[0].id, "input");
});

test("新对话页直接发送后，地址栏过渡未完成前仍保持消息、员工与输入区，返回新对话不复用旧过渡状态", async () => {
  const page = workspace({fresh: true, deferHistory: true});
  page.setModelLoaded(false);
  page.render().setDraft("直接在新对话页发送");
  const pending = page.render().submitMessageContent();
  const sendingView = page.render();
  assert.equal(sendingView.conversationId, null);
  assert.equal(sendingView.messages.length, 2);
  assert.equal(sendingView.agentName, "工作助手");

  page.resolve(accepted);
  await pending;

  // Next.js replaceState 经 startTransition 延迟更新 pathname，此时路由仍在 /new-task，但不能闪回欢迎页。
  assert.equal(page.path(), "/new-task");
  assert.equal(page.locationPathname(), "/conversations/conversation");
  const beforeRouteSync = page.render();
  assert.equal(beforeRouteSync.conversationId, "conversation");
  assert.equal(beforeRouteSync.messages.length, 2);
  assert.equal(beforeRouteSync.messages[0].clientId, sendingView.messages[0].clientId);
  assert.equal(beforeRouteSync.messages[1].clientId, sendingView.messages[1].clientId);
  assert.equal(beforeRouteSync.messages[1].localDelivery, "accepted");
  assert.equal(beforeRouteSync.agentName, "工作助手");
  assert.equal(beforeRouteSync.employeeAppearance?.icon, "Notes");
  assert.equal(beforeRouteSync.approvalPolicy.value, "auto_approve");
  assert.equal(beforeRouteSync.model.initialOptions?.selection?.modelProfileId, "model-1");
  assert.equal(beforeRouteSync.project.selected?.id, "project-1");
  assert.equal(beforeRouteSync.composerHandoff, true);

  // 路由切换完成但快照尚未到达，仍保持过渡显示，且清除发起路由绑定。
  page.commitHistory();
  assert.equal(page.path(), "/conversations/conversation");
  const afterRouteSync = page.render();
  assert.equal(afterRouteSync.conversationId, "conversation");
  assert.equal(afterRouteSync.messages.length, 2);
  assert.equal(afterRouteSync.messages[0].clientId, sendingView.messages[0].clientId);
  assert.equal(afterRouteSync.agentName, "工作助手");
  assert.equal(afterRouteSync.composerHandoff, true);

  // 快照返回且模型选项就绪后自动释放过渡上下文，不再残留。
  page.stream.state = {snapshot: {
    conversation: {id: "conversation", agentId: "agent", agentName: "工作助手", agentIcon: "Notes", agentColor: "orange",
      approvalPolicy: "auto_approve", mode: "normal", status: "active", canContinue: true},
    messages: afterRouteSync.messages.map(message => ({
      ...message, blocks: [], localDelivery: undefined, status: message.role === "user" ? "completed" : "streaming"
    })),
    attachmentsEnabled: true, activeRun: {id: "run", mode: "interactive", status: "running", outputMessageId: "output"}
  }, latestRun: {id: "run", mode: "interactive", status: "running", outputMessageId: "output"}};
  page.setModelLoaded(true);
  const settled = page.render();
  assert.equal(settled.composerHandoff, false);
  assert.equal(settled.messages.length, 2);
  assert.equal(settled.messages[0].clientId, sendingView.messages[0].clientId);
  assert.equal(settled.messages[1].localDelivery, undefined);

  // 路由已切到对话后若在快照到达前直接切回 /new-task，不能重新激活之前的创建过渡状态。
  const backPage = workspace({fresh: true, deferHistory: true});
  backPage.render().setDraft("再次创建");
  const second = backPage.render().submitMessageContent();
  backPage.resolve(accepted);
  await second;
  backPage.render();
  backPage.commitHistory();
  backPage.render();
  backPage.navigate("/new-task?agent=agent");
  const returnedNewTask = backPage.render();
  assert.equal(returnedNewTask.conversationId, null);
  assert.equal(returnedNewTask.messages.length, 0);
  assert.equal(returnedNewTask.composerHandoff, false);
});

test("工作台交接在路由尚未切到新对话前不提前结束交接，避免快照先到时闪回空对话", () => {
  const submission = {...sending(), accepted};
  const handoff = {value: {userId: "user", enterpriseId: "enterprise", conversationId: "conversation", status: "accepted",
    submission, employee: {agentId: "agent", name: "工作助手"}, focusInput: false}, finish(id) {
    if (handoff.value?.conversationId === id) {
      handoff.value = null;
    }
  }};
  const page = workspace({fresh: true, handoff, search: `agent=agent&submission=${submission.id}`, deferHistory: true});
  page.stream.state = {snapshot: {
    conversation: {id: "conversation", agentId: "agent", agentName: "工作助手", mode: "normal", status: "active", canContinue: true},
    messages: [], attachmentsEnabled: true, activeRun: null
  }, latestRun: null};
  const beforeSync = page.render();
  assert.equal(beforeSync.conversationId, "conversation");
  assert.notEqual(handoff.value, null);
  page.commitHistory();
  const afterSync = page.render();
  assert.equal(afterSync.conversationId, "conversation");
  assert.equal(handoff.value, null);
});
