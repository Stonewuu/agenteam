import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";
import {chineseText, loadSource} from "./i18n-test-runtime.mjs";

const submissions = loadSource("features/agent/lib/conversation-submissions");
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
function workspace({queued = false, fresh = false} = {}) {
  const slots = [];
  const effects = [];
  const requests = [];
  let cursor = 0;
  let changed = false;
  let complete;
  let fail;
  let path = fresh ? "/new-task" : "/conversations/conversation";
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
    useMemo: operation => operation(), useCallback: operation => operation, useId: () => "scope",
    useEffect: operation => effects.push(operation)
  };
  const files = {items: [{reference: attachment}], fileIds: [attachment.id], ready: true, clear() {
    files.items = [];
    files.fileIds = [];
  }};
  const skills = {selected: [{name: "文件技能", versionId: "skill"}], loading: false, error: "", clear() {
    skills.selected = [];
  }};
  const employee = {agentId: "agent", name: "工作助手", canRun: true, attachmentsEnabled: true};
  const stream = {state: fresh ? null : {snapshot: {
    conversation: {id: "conversation", agentId: "agent", mode: "normal", status: "active", canContinue: true},
    messages: [], attachmentsEnabled: true, activeRun: queued ? {id: "previous", mode: "interactive", status: "running"} : null
  }, latestRun: null}, reload() {}, updateMetadata() {}};
  const policy = {ready: true, saving: false, value: "default", resetDraft() {}};
  const modules = {
    react, "next/navigation": {usePathname: () => path, useSearchParams: () => new URLSearchParams("agent=agent")},
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
    "./use-conversation-approval-policy": {useConversationApprovalPolicy: () => policy},
    "./use-conversation-model": {useConversationModel: () => ({...policy, configurable: false})},
    "@/features/project/hooks/use-conversation-project": {useConversationProject: () => policy},
    "../components/composer-handoff-context": {useComposerHandoff: () => null}
  };
  const exports = {};
  vm.runInNewContext(compiled, {exports, crypto, URLSearchParams, window: {
    addEventListener() {}, removeEventListener() {}, history: {pushState(_, __, next) {
      path = next;
    }, replaceState(_, __, next) {
      path = next;
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
  return {render, requests, files, skills, stream, resolve: value => complete(value), reject: error => fail(error), navigate: value => {
    path = value;
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
