import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const {navigationConversation, conversationTarget, conversationSelected, retainConversationOrder, rolePreferenceKey, saveConversationPreference} = loadSource("features/agent/lib/conversation-navigation");
const {conversationReadingPosition, rememberConversationPosition} = loadSource("features/agent/lib/conversation-reading");
const group = {taskId: "task", ownerConversationId: "hr", roles: [{conversationId: "hr"}, {conversationId: "finance"}]};

test("任务默认打开负责人，保存的岗位仅在当前任务和当前账号中有效", () => {
  const savedWindow = globalThis.window;
  const values = new Map();
  globalThis.window = {localStorage: {getItem: key => values.get(key), setItem: (key, value) => values.set(key, value)}, dispatchEvent() {}};
  try {
    const value = {id: "hr", collaboration: group};
    assert.equal(conversationTarget(value, "user", "enterprise"), "hr");
    saveConversationPreference(rolePreferenceKey("user", "enterprise", "task"), "finance");
    assert.equal(conversationTarget(value, "user", "enterprise"), "finance");
    assert.equal(conversationTarget(value, "another-user", "enterprise"), "hr");
    assert.equal(conversationTarget(value, "user", "another-enterprise"), "hr");
    saveConversationPreference(rolePreferenceKey("user", "enterprise", "task"), "unrelated-conversation");
    assert.equal(conversationTarget(value, "user", "enterprise"), "hr");
  } finally {
    globalThis.window = savedWindow;
  }
});

test("搜索结果指定的岗位和旧岗位链接不被默认负责人替代", () => {
  const value = {id: "finance", collaboration: group, matchedRole: true};
  assert.equal(conversationTarget(value, "user", "enterprise"), "finance");
  assert.equal(conversationSelected(value, "hr"), true);
  assert.equal(conversationSelected(value, "finance"), true);
  assert.equal(conversationSelected(value, "other"), false);
});

test("列表刷新保留任务位置和最新内容，代表岗位变化不产生重复条目", () => {
  const previous = [{id: "hr", revision: "1", collaboration: group, navigationKey: "task:one", title: "旧标题"}, {id: "chat", revision: "2", navigationKey: "conversation:chat"}];
  const incoming = [{id: "new", revision: "1", navigationKey: "conversation:new"}, {id: "chat", revision: "3", navigationKey: "conversation:chat"}, {id: "finance", revision: "1", collaboration: group, navigationKey: "task:one", title: "新标题"}];
  const result = retainConversationOrder(previous, incoming);
  assert.deepEqual(result.map(value => value.id), ["new", "finance", "chat"]);
  assert.equal(result[1].title, "新标题");
  assert.equal(previous[0].title, "旧标题");
});

test("导航展示使用任务名称和整项收藏，实际操作仍使用岗位对话编号和版本", () => {
  const item = {key: "task:one", title: "工资统计", updatedAt: "today", favorite: true,
    conversation: {id: "finance", revision: "9", title: "GC-xxx 财务", favorite: false}, collaboration: group, matchedRole: false};
  const result = navigationConversation(item);
  assert.equal(result.id, "finance");
  assert.equal(result.revision, "9");
  assert.equal(result.title, "工资统计");
  assert.equal(result.favorite, true);
  assert.equal(item.conversation.title, "GC-xxx 财务");
});

test("阅读位置按完整账号与会话范围保存，不覆盖其他岗位的位置", () => {
  const hr = {top: 500, anchorId: "message-one", offset: -30, atBottom: false};
  const finance = {top: 900, anchorId: "message-two", offset: -50, atBottom: false};
  rememberConversationPosition("user:enterprise:hr", hr);
  rememberConversationPosition("user:enterprise:finance", finance);
  assert.deepEqual(conversationReadingPosition("user:enterprise:hr"), hr);
  assert.deepEqual(conversationReadingPosition("user:enterprise:finance"), finance);
  assert.equal(conversationReadingPosition("other-user:enterprise:hr"), undefined);
});
