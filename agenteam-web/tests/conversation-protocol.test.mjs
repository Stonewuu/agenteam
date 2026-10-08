import assert from "node:assert/strict";
import { test } from "node:test";
import { createRequire } from "node:module";

const load = createRequire(import.meta.url);
const { fromSnapshot, applyConversationFrame } = load("../.test-build/conversation/agent/state/conversation-event-state.js");
const { parseConversationFrame, SnapshotRequired } = load("../.test-build/conversation/agent/state/conversation-protocol.js");
const { displayMessages, mergeRunApprovals } = load("../.test-build/conversation/agent/lib/conversation-display.js");
const time = "2026-09-14T08:00:00Z";

test("子助手历史内容保留实际图标和配色", () => {
  const child = { ...block("child-avatar", "subagent"), label: "写作助手", agentIcon: "NotebookPen", agentColor: "mint" };
  const state = fromSnapshot(snapshot("1", [child]));
  const displayed = displayMessages(state)[0].blocks[0];
  assert.equal(displayed.kind, "agent");
  assert.equal(displayed.agentIcon, "NotebookPen");
  assert.equal(displayed.agentColor, "mint");
});

test("历史内置工具使用中文标题，原调用名称和外部同名工具保持原样", () => {
  const original = { ...block("old-tool", "tool"), label: "待办管理：todo_create" };
  original.tool.name = "todo_create";
  const remote = { ...original, id: "remote-tool", label: "外部插件：todo_create", displayOrder: 2 };
  const localized = { ...original, id: "new-tool", label: "待办管理 · 创建待办", displayOrder: 3 };
  const state = fromSnapshot(snapshot("3", [original, remote, localized]));
  const blocks = displayMessages(state)[0].blocks;
  assert.deepEqual(blocks.map(item => item.label), ["待办管理 · 创建待办", "外部插件：todo_create", "待办管理 · 创建待办"]);
  assert.equal(blocks[0].name, "todo_create");
  assert.equal(state.snapshot.messages[0].blocks[0].label, "待办管理：todo_create");
});

test("旧确认标题与目标显示一致，未知工具名称不推测翻译", () => {
  const { localizeSavedToolLabel, toolDisplayName } = load("../.test-build/conversation/plugin/lib/tool-display-name.js");
  assert.equal(localizeSavedToolLabel("定时任务 / schedule_create"), "定时任务 · 创建计划");
  assert.equal(localizeSavedToolLabel("待办管理：todo_update"), "待办管理 · 修改待办");
  assert.equal(localizeSavedToolLabel("地址：https://example.com/todo_create"), "地址：https://example.com/todo_create");
  assert.equal(toolDisplayName({ name: "todo_create" }), "todo_create");
  assert.equal(toolDisplayName({ name: "todo_create" }, "todo_management"), "创建待办");
  assert.equal(toolDisplayName({ name: "ask_question", displayName: "查询仓库文档" }), "查询仓库文档");
});

test("子智能体启动和继续调用使用子智能体分组，原父子关系和业务工具保持不变", () => {
  const { groupConversationBlocks, isSubagentTool } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const child = { kind: "agent", id: "child", label: "写作助手", status: "running", order: 1, summary: "", blocks: [] };
  const tools = ["agent_spawn", "agent_send", "query_tool"].map((name, index) => ({ kind: "tool", id: name, name, label: "子智能体", sourceKind: null,
    status: "running", order: index, blocks: index === 0 ? [child] : [] }));
  const groups = groupConversationBlocks(tools);
  assert.deepEqual(groups.map(group => group.kind), ["agent", "tool"]);
  assert.equal(groups[0].blocks[0], tools[0]);
  assert.equal(groups[0].blocks[0].blocks[0], child);
  assert.equal(isSubagentTool(tools[2]), false);
  assert.equal(isSubagentTool({ ...tools[0], sourceKind: "knowledge" }), false);
  assert.equal(isSubagentTool({ ...tools[0], label: "自定义插件：agent_spawn", blocks: [] }), false);
});

function block(id, type = "text", parentBlockId = null, order = 1) {
  return { id, type, parentBlockId, displayOrder: order, revision: "1", text: "", status: "running", stepId: null, approvalId: null,
    file: null, citation: null, label: null, tool: type === "tool" ? { toolCallId: id, name: "query_tool", sourceKind: null, input: "查询问题", result: "", callStatus: "completed", resultStatus: "running" } : null };
}
function snapshot(lastSequence = "3", blocks = []) {
  return { conversation: { id: "conversation", revision: "2", title: "讨论", agentId: "employee", agentName: "员工", mode: "normal", status: "active", favorite: false,
    activeRunId: "run", canContinue: false, unavailableReason: null, createdAt: time, updatedAt: time },
    messages: [{ id: "output", runId: "run", attemptNo: 1, role: "assistant", content: "", blocks, status: "streaming", attachments: [], feedback: null, createdAt: time, updatedAt: time }],
    activeRun: { id: "run", conversationId: "conversation", outputMessageId: "output", status: "running", lastSequence }, lastSequence, hasOlderMessages: false, nextBeforeMessageId: null, attachmentsEnabled: false };
}
function event(sequence, type, payload) {
  return { kind: "event", event: { protocolVersion: 1, enterpriseId: "enterprise", conversationId: "conversation", runId: "run", eventId: `event-${sequence}`,
    sequence, createdAt: time, type, payload } };
}
function apply(state, frame) {
 return applyConversationFrame(state, frame, "enterprise"); 
}

function liveSnapshot(position = "100", blocks = []) {
  const value = snapshot("10", blocks);
  value.protocolVersion = 2;
  value.streamCursor = { generation: "generation-1", sequence: position };
  value.liveSteps = [];
  value.approvals = [];
  value.messages[0].content = blocks.filter(item => item.type === "text").map(item => item.text).join("\n\n");
  return value;
}

function liveEvent(position, type, payload, databaseVersion = "10") {
  const value = event(position, type, payload);
  value.event.protocolVersion = 2;
  value.event.generation = "generation-1";
  value.event.databaseVersion = databaseVersion;
  return value;
}

test("第二版刷新快照先恢复累计全文，再精确追加增量且不推进数据库版本", () => {
  const text = { ...block("text"), revision: "70", text: "已经输出的正文" };
  const initial = fromSnapshot(liveSnapshot("100", [text]));
  const delta = liveEvent("101", "message.delta", { messageId: "output", blockId: "text", baseRevision: "70", revision: "71", delta: "，后续内容" });
  const next = apply(initial, delta);
  assert.equal(next.snapshot.messages[0].content, "已经输出的正文，后续内容");
  assert.equal(next.snapshot.streamCursor.sequence, "101");
  assert.equal(next.snapshot.lastSequence, "10");
  assert.equal(next.snapshot.activeRun.lastSequence, "10");
  assert.equal(apply(next, delta), next);
});

test("第二版内容块完整替换允许跳过未保存版本，完成后不会重复追加正文", () => {
  const initial = fromSnapshot(liveSnapshot("100", [{ ...block("text"), text: "前半段" }]));
  const completed = { ...block("text"), revision: "1001", text: "前半段和后半段", status: "completed" };
  const next = apply(initial, liveEvent("101", "block.updated", { messageId: "output", block: completed }, "11"));
  assert.equal(next.snapshot.messages[0].content, "前半段和后半段");
  assert.equal(next.snapshot.messages[0].blocks[0].revision, "1001");
  assert.equal(next.snapshot.lastSequence, "11");
  assert.equal(next.snapshot.streamCursor.sequence, "101");
});

test("Redis 生命周期变化或序号不连续时保留当前正文并要求重新加载", () => {
  const initial = fromSnapshot(liveSnapshot("9007199254740992", [block("text")]));
  const delta = liveEvent("9007199254740993", "message.delta", { messageId: "output", blockId: "text", baseRevision: "1", revision: "2", delta: "新增" });
  assert.equal(apply(initial, delta).snapshot.streamCursor.sequence, "9007199254740993");
  delta.event.generation = "generation-2";
  assert.throws(() => apply(initial, delta), SnapshotRequired);
  delta.event.generation = "generation-1";
  delta.event.sequence = "9007199254740994";
  assert.throws(() => apply(initial, delta), SnapshotRequired);
  assert.equal(initial.snapshot.messages[0].content, "");
});

test("第二版旧执行终态晚到不会关闭正在输出的新执行", () => {
  const value = liveSnapshot("100", [block("text")]);
  const old = { ...value.activeRun, status: "failed", lastSequence: "11" };
  value.activeRun = { ...value.activeRun, id: "new-run", outputMessageId: "new-output" };
  value.conversation.activeRunId = "new-run";
  value.messages.push({ ...value.messages[0], id: "new-output", runId: "new-run", blocks: [] });
  const state = apply(fromSnapshot(value), liveEvent("101", "run.failed", old, "11"));
  assert.equal(state.snapshot.activeRun.id, "new-run");
  assert.equal(state.snapshot.conversation.activeRunId, "new-run");
  assert.equal(state.latestRun.id, "new-run");
  assert.equal(state.snapshot.messages[0].status, "failed");
});

test("第二版读取确认必须与快照生命周期一致", () => {
  const state = fromSnapshot(liveSnapshot());
  const frame = parseConversationFrame('event: stream.ready\ndata: {"lastSequence":"100","generation":"generation-1"}');
  assert.equal(apply(state, frame).replaying, false);
  assert.throws(() => apply(state, { ...frame, generation: "generation-2" }), SnapshotRequired);
  const delta = liveEvent("101", "message.delta", { messageId: "output", blockId: "text", baseRevision: "1", revision: "2", delta: "片段" });
  const parsed = parseConversationFrame(`id: 101\nevent: message.delta\ndata: ${JSON.stringify(delta.event)}`);
  assert.deepEqual(parsed, delta);
});

test("自动尝试保留失败正文，后续尝试使用独立消息并保持同一活动任务", () => {
  const text = { ...block("first-text"), text: "第一轮部分结果", status: "failed" };
  const initial = snapshot("3", [text]); initial.messages[0].content = text.text;
  let state = fromSnapshot(initial);
  state = apply(state, event("4", "attempt.updated", { id: "attempt-1", attemptNo: 1, outputMessageId: "output", status: "failed",
    startedAt: time, finishedAt: time, errorSummary: "模型连接暂时失败" }));
  const next = { ...initial.messages[0], id: "second-output", attemptNo: 2, blocks: [], content: "", status: "pending" };
  state = apply(state, event("5", "message.created", next));
  state = apply(state, event("6", "run.resumed", { ...initial.activeRun, currentAttemptNo: 2, outputMessageId: next.id, status: "queued", lastSequence: "6", nextAttemptAt: "2026-09-14T08:00:30Z" }));
  assert.equal(state.snapshot.activeRun.id, "run"); assert.equal(state.snapshot.activeRun.currentAttemptNo, 2);
  assert.equal(state.snapshot.messages[0].status, "failed"); assert.equal(state.snapshot.messages[0].content, text.text);
  assert.equal(state.snapshot.messages[1].status, "pending");
  assert.deepEqual(displayMessages(state).map((message) => [message.attemptNo, message.hasMultipleAttempts]), [[1, true], [2, true]]);
  assert.deepEqual(displayMessages(fromSnapshot(state.snapshot)).map((message) => [message.attemptNo, message.hasMultipleAttempts]), [[1, true], [2, true]]);
  assert.throws(() => apply(state, event("7", "message.delta", { messageId: "output", blockId: text.id, baseRevision: "1", revision: "2", delta: "旧尝试晚到正文" })), SnapshotRequired);
  assert.throws(() => apply(fromSnapshot(initial), event("4", "attempt.updated", { id: "wrong-attempt", attemptNo: 2, outputMessageId: "output", status: "failed" })), SnapshotRequired);
});

test("超过普通数字范围的连续序号仍精确，重复事件不会重复追加", () => {
  const initial = fromSnapshot(snapshot("9007199254740992", [block("text")]));
  const delta = event("9007199254740993", "message.delta", { messageId: "output", blockId: "text", baseRevision: "1", revision: "2", delta: "正文" });
  const next = apply(initial, delta);
  assert.equal(next.snapshot.lastSequence, "9007199254740993"); assert.equal(next.snapshot.messages[0].content, "正文");
  assert.equal(apply(next, delta), next); assert.equal(initial.snapshot.messages[0].content, "");
  assert.throws(() => apply(next, event("9007199254740995", "step.updated", {})), SnapshotRequired);
});

test("编号不连续或块版本不符时要求完整快照，现有内容不被部分修改", () => {
  const state = fromSnapshot(snapshot("3", [block("text")]));
  assert.throws(() => apply(state, event("5", "message.delta", { messageId: "output", blockId: "text", baseRevision: "1", revision: "2", delta: "错误追加" })), SnapshotRequired);
  assert.throws(() => apply(state, event("4", "message.delta", { messageId: "output", blockId: "text", baseRevision: "2", revision: "3", delta: "错误追加" })), SnapshotRequired);
  assert.equal(state.snapshot.messages[0].content, ""); assert.equal(state.snapshot.lastSequence, "3");
  const other = event("4", "step.updated", {}); other.event.enterpriseId = "other";
  assert.throws(() => apply(state, other), SnapshotRequired);
});

test("工具和并发子任务按已保存的父块和顺序更新，快照不会播放历史动画", () => {
  let state = fromSnapshot(snapshot());
  const blocks = [block("tool-a", "tool", null, 1), block("tool-b", "tool", null, 2), block("child-a", "subagent", "tool-a", 3),
    block("child-b", "subagent", "tool-b", 4), block("text-b", "text", "child-b", 5), block("text-a", "text", "child-a", 6)];
  let sequence = 3;
  for (const value of blocks) {
state = apply(state, event(String(++sequence), "block.updated", { messageId: "output", block: value }));
}
  state = apply(state, event(String(++sequence), "message.delta", { messageId: "output", blockId: "text-b", baseRevision: "1", revision: "2", delta: "乙内容" }));
  assert.equal(state.liveBlocks.size, 0);
  state = apply(state, { kind: "ready", lastSequence: String(sequence) });
  state = apply(state, event(String(++sequence), "message.delta", { messageId: "output", blockId: "text-a", baseRevision: "1", revision: "2", delta: "甲内容" }));
  assert.deepEqual([...state.liveBlocks], ["text-a"]);
  const saved = state.snapshot.messages[0].blocks;
  assert.equal(saved.find((value) => value.id === "child-a").parentBlockId, "tool-a");
  assert.equal(saved.find((value) => value.id === "text-b").text, "乙内容");
  assert.deepEqual(saved.map((value) => value.displayOrder), [1, 2, 3, 4, 5, 6]);
  const reloaded = fromSnapshot(state.snapshot); assert.equal(reloaded.liveBlocks.size, 0);
  assert.throws(() => apply(state, event(String(sequence + 1), "block.updated", { messageId: "output", block: { ...blocks[2], parentBlockId: "tool-b", revision: "2" } })), SnapshotRequired);
});

test("控制事件和心跳没有业务编号，旧 Redis 编号和不一致正文被拒绝", () => {
  assert.deepEqual(parseConversationFrame('event: stream.ready\ndata: {"lastSequence":"3"}'), { kind: "ready", lastSequence: "3" });
  assert.equal(parseConversationFrame(": 保持连接"), null);
  assert.throws(() => parseConversationFrame('id: 4\nevent: stream.ready\ndata: {"lastSequence":"3"}'), SnapshotRequired);
  const business = event("4", "step.updated", {}).event;
  assert.throws(() => parseConversationFrame(`id: 1234-0\nevent: step.updated\ndata: ${JSON.stringify(business)}`), SnapshotRequired);
  assert.throws(() => parseConversationFrame(`id: 5\nevent: step.updated\ndata: ${JSON.stringify(business)}`), SnapshotRequired);
  assert.deepEqual(parseConversationFrame(`id: 4\nevent: step.updated\ndata: ${JSON.stringify(business)}`), { kind: "event", event: business });
});

test("取消终态保留部分正文，后到的内容修改不能使消息重新变成生成中", () => {
  const text = { ...block("text"), text: "部分结果" }; const original = snapshot("3", [text]); original.messages[0].content = text.text;
  let state = fromSnapshot(original);
  state = apply(state, event("4", "run.cancelled", { ...original.activeRun, lastSequence: "4", status: "cancelled", finishedAt: time }));
  assert.equal(state.snapshot.activeRun, null); assert.equal(state.snapshot.messages[0].status, "cancelled"); assert.equal(state.snapshot.messages[0].content, "部分结果");
  assert.throws(() => apply(state, event("5", "message.delta", { messageId: "output", blockId: "text", baseRevision: "1", revision: "2", delta: "晚到内容" })), SnapshotRequired);
});

test("工作流节点中的研究子任务留在对应节点内，根对话不会重复显示", () => {
  const blocks = [block("flow", "workflow", null, 1), block("left", "workflow", "flow", 2), block("right", "workflow", "flow", 3),
    block("left-tool", "tool", "left", 4), block("right-tool", "tool", "right", 5),
    block("left-child", "subagent", "left-tool", 6), block("right-child", "subagent", "right-tool", 7),
    { ...block("child-text", "text", "left-child", 8), text: "左侧研究结果" },
    { ...block("result", "text", null, 9), text: "最终结果" }];
  const state = fromSnapshot({ ...snapshot("3", blocks), messages: [{ ...snapshot("3", blocks).messages[0], content: "最终结果" }] });
  const [message] = displayMessages(state);
  assert.deepEqual(message.blocks.map((value) => value.id), ["flow", "result"]);
  const [left, right] = message.blocks[0].blocks;
  assert.deepEqual(left.blocks.map((value) => value.id), ["left-tool"]);
  assert.deepEqual(right.blocks.map((value) => value.id), ["right-tool"]);
  assert.equal(left.blocks[0].blocks[0].id, "left-child");
  assert.equal(right.blocks[0].blocks[0].id, "right-child");
  assert.equal(left.blocks[0].blocks[0].blocks[0].content, "左侧研究结果");
  assert.equal(left.blocks[0].blocks[0].blocks[0].animate, false);
});

test("多层子智能体中的文本、公开摘要、工具和人工确认不被提升或丢弃", () => {
  const blocks = [
    { ...block("root-tool", "tool", null, 1), text: "已分配研究任务" },
    { ...block("child", "subagent", "root-tool", 2), text: "研究结果摘要" },
    { ...block("summary", "execution_summary", "child", 3), text: "先核对来源，再整理结论" },
    { ...block("child-text", "text", "child", 4), text: "已找到资料" },
    { ...block("write-tool", "tool", "child", 5), status: "waiting_approval" },
    { ...block("approval", "approval", "write-tool", 6), approvalId: "confirmation" },
    block("grandchild", "subagent", "write-tool", 7),
    { ...block("grandchild-text", "text", "grandchild", 8), text: "内层回复" },
  ];
  const [message] = displayMessages(fromSnapshot(snapshot("3", blocks)));
  const root = message.blocks[0], child = root.blocks[0], write = child.blocks[2];
  assert.equal(message.blocks.length, 1);
  assert.equal(root.summary, "已分配研究任务");
  assert.equal(child.summary, "研究结果摘要");
  assert.deepEqual(child.blocks.map(value => value.kind), ["summary", "text", "tool"]);
  assert.equal(child.blocks[0].content, "先核对来源，再整理结论");
  assert.deepEqual(write.blocks.map(value => value.kind), ["approval", "agent"]);
  assert.equal(write.status, "waiting_approval");
  assert.equal(write.blocks[0].approvalId, "confirmation");
  assert.equal(write.blocks[1].blocks[0].content, "内层回复");
  const flatten = values => values.flatMap(value => [value.id, ...flatten(value.blocks)]);
  assert.deepEqual(flatten(message.blocks), blocks.map(value => value.id));
});

test("人工确认结束事件即时更新，重复事件和晚到查询不会恢复旧确认按钮", () => {
  const pending = { id: "confirmation", requestHash: "request", revision: "1", status: "pending", expiresAt: time,
    summary: { title: "确认写入", target: "验收目标", description: "检查确认状态", content: "验收内容", irreversibleEffect: null } };
  const initial = fromSnapshot(snapshot());
  const created = apply(initial, event("4", "approval.created", pending));
  const approved = { ...pending, status: "approved", revision: "2" };
  const frame = event("5", "approval.resolved", approved);
  const resolved = apply(created, frame);
  assert.equal(initial.approvals.size, 0);
  assert.equal(created.approvals.get(pending.id).status, "pending");
  assert.equal(resolved.approvals.get(pending.id).status, "approved");
  assert.equal(apply(resolved, frame), resolved);
  assert.equal(mergeRunApprovals(resolved.approvals, [pending]).get(pending.id).status, "approved");
  assert.equal(mergeRunApprovals(undefined, [approved]).get(pending.id).status, "approved");
  assert.throws(() => apply(resolved, event("6", "approval.resolved", pending)), SnapshotRequired);
});

test("新内容提示区分追加回复、增量变化与加载旧消息或评价", async () => {
  const { hasNewConversationContent } = await import("../.test-build/conversation/agent/lib/conversation-reading.js");
  const message = snapshot("3", [block("text")]).messages[0];
  assert.equal(hasNewConversationContent([], [message]), false);
  assert.equal(hasNewConversationContent([message], [{ ...message, feedback: "positive" }]), false);
  assert.equal(hasNewConversationContent([message], [{ ...message, id: "older" }, message]), false);
  assert.equal(hasNewConversationContent([message], [message, { ...message, id: "new" }]), true);
  assert.equal(hasNewConversationContent([message], [{ ...message, blocks: [{ ...message.blocks[0], revision: "2", text: "新正文" }] }]), true);
  assert.equal(hasNewConversationContent([message], [{ ...message, status: "completed" }]), true);
});

function step(id, parentStepId = null, kind = "model", displayOrder = 0) {
  return { id, parentStepId, attemptId: "attempt-1", kind, displayOrder, title: "生成回复", status: "running", publicSummary: null, startedAt: time, finishedAt: null, workflow: null };
}

test("思考增量实时显示但不进入最终正文，重放和完成后不播放动画", () => {
  let state = fromSnapshot(snapshot("3", [block("thought", "thinking"), block("answer", "text", null, 2)]));
  const first = event("4", "message.delta", { messageId: "output", blockId: "thought", baseRevision: "1", revision: "2", delta: "先核对事实。" });
  state = apply(state, first);
  assert.equal(displayMessages(state)[0].blocks[0].animate, false);
  state = apply(state, { kind: "ready", lastSequence: "4" });
  state = apply(state, event("5", "message.delta", { messageId: "output", blockId: "thought", baseRevision: "2", revision: "3", delta: "再作答。" }));
  assert.equal(displayMessages(state)[0].blocks[0].kind, "thinking");
  assert.equal(displayMessages(state)[0].blocks[0].animate, true);
  assert.equal(state.snapshot.messages[0].content, "");
  assert.equal(apply(state, first), state);
  state = apply(state, event("6", "block.updated", { messageId: "output", block: { ...state.snapshot.messages[0].blocks[0], revision: "4", status: "completed" } }));
  state = apply(state, event("7", "message.delta", { messageId: "output", blockId: "answer", baseRevision: "1", revision: "2", delta: "最终答复" }));
  assert.equal(state.snapshot.messages[0].content, "最终答复");
  assert.equal(displayMessages(state)[0].blocks[0].animate, false);
  assert.equal(displayMessages(fromSnapshot(state.snapshot))[0].blocks[0].content, "先核对事实。再作答。");
  assert.equal(displayMessages(fromSnapshot(state.snapshot))[0].blocks[1].animate, false);
  assert.throws(() => apply(state, event("8", "message.delta", { messageId: "output", blockId: "thought", baseRevision: "3", revision: "5", delta: "旧版本" })), SnapshotRequired);
});

test("大类归并保留顺序、工具与子任务身份，未对应工具的确认独立显示", () => {
  const { groupConversationBlocks, groupBlockStatus } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const raw = [block("thinking-1", "thinking", null, 1), block("thinking-2", "thinking", null, 2),
    block("text", "text", null, 3), block("tool-a", "tool", null, 4), block("tool-b", "tool", null, 5),
    block("child", "subagent", "tool-a", 6), block("child-thought", "thinking", "child", 7),
    { ...block("confirm", "approval", null, 8), approvalId: "confirmation" }, block("tool-c", "tool", null, 9), block("thinking-3", "thinking", null, 10)];
  const blocks = displayMessages(fromSnapshot(snapshot("3", raw)))[0].blocks;
  const groups = groupConversationBlocks(blocks);
  assert.deepEqual(groups.map(value => value.kind), ["thinking", "text", "tool", "approval", "tool", "thinking"]);
  assert.deepEqual(groups[0].blocks.map(value => value.id), ["thinking-1", "thinking-2"]);
  assert.deepEqual(groups[2].blocks.map(value => value.id), ["tool-a", "tool-b"]);
  assert.equal(groups[2].blocks[0].blocks[0].blocks[0].kind, "thinking");
  assert.equal(groups[3].blocks[0].approvalId, "confirmation");
  assert.equal(blocks.length, 8);
  assert.equal(groups[2].id, groupConversationBlocks(blocks.filter(value => value.id !== "tool-b"))[2].id);
  assert.equal(groupBlockStatus([{ ...groups[2].blocks[0], status: "completed" }, { ...groups[2].blocks[1], status: "running" }]), "running");
  assert.equal(groupBlockStatus([{ ...groups[2].blocks[0], status: "failed" }, { ...groups[2].blocks[1], status: "completed" }]), "failed");
});

test("多个工具的确认按步骤分别合入，原快照不被修改且重复合并不重复显示", () => {
  const { mergeToolConfirmations, groupConversationBlocks } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const raw = [{ ...block("tool-a", "tool", null, 1), stepId: "step-a" }, { ...block("tool-b", "tool", null, 2), stepId: "step-b" },
    { ...block("confirm-b", "approval", null, 3), stepId: "step-b", approvalId: "decision-b" },
    { ...block("confirm-a", "approval", null, 4), stepId: "step-a", approvalId: "decision-a" }];
  const original = displayMessages(fromSnapshot(snapshot("3", raw)))[0].blocks;
  const merged = mergeToolConfirmations(original);
  assert.deepEqual(merged.map(value => value.id), ["tool-a", "tool-b"]);
  assert.deepEqual(merged.map(value => value.blocks.map(child => child.approvalId)), [["decision-a"], ["decision-b"]]);
  assert.deepEqual(groupConversationBlocks(merged).map(value => value.kind), ["tool"]);
  assert.equal(original.length, 4); assert.equal(original[0].blocks.length, 0);
  assert.deepEqual(mergeToolConfirmations(merged), merged);
});

test("嵌套工具确认保留所属子任务，工作流确认和无法唯一对应的确认继续可见", () => {
  const { mergeToolConfirmations } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const raw = [block("agent-a", "subagent", null, 1), block("agent-b", "subagent", null, 2),
    { ...block("tool-a", "tool", "agent-a", 3), stepId: "same-step" }, { ...block("tool-b", "tool", "agent-b", 4), stepId: "same-step" },
    { ...block("confirm-a", "approval", "agent-a", 5), stepId: "same-step", approvalId: "a" },
    { ...block("confirm-b", "approval", "agent-b", 6), stepId: "same-step", approvalId: "b" },
    { ...block("flow", "workflow", null, 7), stepId: "workflow-step" },
    { ...block("flow-confirm", "approval", "flow", 8), stepId: "workflow-step", approvalId: "flow-approval" },
    { ...block("ambiguous-tool-a", "tool", null, 9), stepId: "ambiguous" }, { ...block("ambiguous-tool-b", "tool", null, 10), stepId: "ambiguous" },
    { ...block("ambiguous-confirm", "approval", null, 11), stepId: "ambiguous", approvalId: "ambiguous-approval" },
    { ...block("unknown-confirm", "approval", null, 12), stepId: "unknown", approvalId: "unknown" }];
  const merged = mergeToolConfirmations(displayMessages(fromSnapshot(snapshot("3", raw)))[0].blocks);
  assert.equal(merged[0].blocks[0].blocks[0].approvalId, "a");
  assert.equal(merged[1].blocks[0].blocks[0].approvalId, "b");
  assert.equal(merged[2].blocks[0].approvalId, "flow-approval");
  assert.deepEqual(merged.slice(3).map(value => value.id), ["ambiguous-tool-a", "ambiguous-tool-b", "ambiguous-confirm", "unknown-confirm"]);
});

test("原本就在工具内的确认及其子内容保持完整", () => {
  const { mergeToolConfirmations } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const raw = [block("tool", "tool", null, 1), { ...block("confirm", "approval", "tool", 2), approvalId: "approval" },
    { ...block("description", "text", "confirm", 3), text: "操作补充内容" }];
  const merged = mergeToolConfirmations(displayMessages(fromSnapshot(snapshot("3", raw)))[0].blocks);
  assert.equal(merged[0].blocks.length, 1);
  assert.equal(merged[0].blocks[0].blocks[0].content, "操作补充内容");
});

test("确认成功后显示等待执行，晚到的待确认查询不能恢复旧按钮或待确认状态", () => {
  const { mergeToolConfirmations, pendingToolConfirmationIds, toolBlockStatus, groupBlockStatus } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const raw = [{ ...block("tool", "tool", null, 1), stepId: "step", status: "waiting_approval" },
    { ...block("confirm", "approval", null, 2), stepId: "step", approvalId: "approval", status: "waiting_approval" }];
  const blocks = mergeToolConfirmations(displayMessages(fromSnapshot(snapshot("3", raw)))[0].blocks);
  const pending = { id: "approval", revision: "1", status: "pending" };
  assert.deepEqual(pendingToolConfirmationIds(blocks, new Map()), ["approval"]);
  const known = new Map([["approval", { ...pending, revision: "2", status: "approved" }]]);
  const approvals = mergeRunApprovals(known, [pending]);
  assert.deepEqual(pendingToolConfirmationIds(blocks, approvals), []);
  assert.equal(toolBlockStatus(blocks[0], approvals), "pending");
  assert.equal(groupBlockStatus(blocks, approvals), "pending");
  assert.equal(toolBlockStatus({ ...blocks[0], status: "completed" }, approvals), "completed");
  assert.equal(toolBlockStatus(blocks[0], new Map([["approval", { ...pending, status: "rejected" }]])), "skipped");
  assert.equal(toolBlockStatus(blocks[0], new Map([["approval", { ...pending, status: "expired" }]])), "cancelled");
  assert.deepEqual(pendingToolConfirmationIds(blocks, new Map([["approval", { ...pending, status: "expired" }]])), []);
});

test("工具参数准备、调度等待与实际执行使用不同提示", () => {
  const { toolStatusLabel } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const tool = { kind: "tool", status: "pending", callStatus: "running", resultStatus: "pending" };
  assert.equal(toolStatusLabel(tool, "pending"), "正在准备执行内容");
  assert.equal(toolStatusLabel({ ...tool, callStatus: "completed" }, "pending"), "等待执行");
  assert.equal(toolStatusLabel({ ...tool, status: "waiting_approval" }, "pending"), "等待执行");
  for (const [status, label] of [["waiting_approval", "等待确认"], ["running", "执行中"], ["completed", "已完成"], ["failed", "执行失败"], ["cancelled", "已停止"], ["skipped", "已跳过"]]) {
    assert.equal(toolStatusLabel({ ...tool, status }, status), label);
  }
});

test("旧文件操作详情隐藏模型说明，保留原参数和确认决定", () => {
  const { approvalDisplayText } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  for (const description of ["在当前对话的 work 或 outputs 目录创建文件。", "准确替换原文，old_text 应唯一匹配；设置 replace_all。", "只在独立沙盒中执行命令，生成后用 export_file 导出。"]) {
    const approval = { kind: "tool", status: "approved", summary: { description: description + "\n请核对文件操作或命令内容后再确认。", content: '{"path":"outputs/demo.html","new_text":"替换内容"}' } };
    assert.deepEqual(approvalDisplayText(approval), { description: "", notice: "" });
    assert.equal(approval.summary.content, '{"path":"outputs/demo.html","new_text":"替换内容"}');
    assert.equal(approval.status, "approved");
    assert.deepEqual(approvalDisplayText({ ...approval, kind: undefined }, true), { description: "", notice: "" });
  }
});

test("外部写入的影响只在等待确认时保留，工作流用户说明不被隐藏", () => {
  const { approvalDisplayText } = load("../.test-build/conversation/agent/lib/conversation-display.js");
  const notice = "此工具可能修改外部数据，请核对全部参数后再决定。";
  const tool = { kind: "tool", status: "pending", summary: { description: "传入工具参数的规则。\n" + notice } };
  assert.deepEqual(approvalDisplayText(tool), { description: "", notice });
  assert.deepEqual(approvalDisplayText({ ...tool, status: "approved" }), { description: "", notice: "" });
  const workflow = { kind: "workflow", status: "pending", summary: { description: "请核对本月报表中的收件人和金额。" } };
  assert.deepEqual(approvalDisplayText(workflow, true), { description: workflow.summary.description, notice: "" });
});

test("没有真实内容的模型生命周期不产生额外卡片，失败说明仍保留", () => {
  const { mergeExecutionSteps } = load("../.test-build/conversation/agent/lib/conversation-steps.js");
  assert.deepEqual(mergeExecutionSteps([], [step("model")]), []);
  const failure = { ...step("failed-model"), status: "failed", publicSummary: "模型连接中断" };
  assert.equal(mergeExecutionSteps([], [failure])[0].summary, "模型连接中断");
});

test("独立步骤增量保留输出消息、顺序和父节点，重复事件不修改旧状态", () => {
  const initial = fromSnapshot(snapshot());
  const value = step("model");
  const created = apply(initial, event("4", "step.updated", value));
  const frame = event("5", "step.updated", { ...value, status: "completed", finishedAt: time });
  const completed = apply(created, frame);
  assert.equal(initial.steps.size, 0);
  assert.equal(created.steps.get(value.id).step.status, "running");
  assert.equal(completed.steps.get(value.id).messageId, "output");
  assert.equal(completed.steps.get(value.id).step.status, "completed");
  assert.equal(apply(completed, frame), completed);
  assert.throws(() => apply(completed, event("6", "step.updated", { ...value, parentStepId: "elsewhere" })), SnapshotRequired);
  const first = apply(initial, event("4", "step.updated", step("a", "b")));
  assert.throws(() => apply(first, event("5", "step.updated", step("b", "a", "model", 1))), SnapshotRequired);
});

test("历史步骤按尝试对应到回复，较旧查询不能把已完成步骤改回运行中", async () => {
  const { messageRunSteps } = await import("../.test-build/conversation/agent/lib/conversation-steps.js");
  const message = displayMessages(fromSnapshot(snapshot()))[0];
  const value = step("model");
  const history = { sequence: "4", error: "", attempts: [{ id: "attempt-1", outputMessageId: message.id }], steps: [value, { ...step("other"), attemptId: "attempt-2" }] };
  const live = new Map([[value.id, { runId: "run", messageId: message.id, sequence: "5", step: { ...value, status: "completed" } }]]);
  assert.deepEqual(messageRunSteps(message, history, live).map(value => [value.id, value.status]), [["model", "completed"]]);
  assert.deepEqual(messageRunSteps({ ...message, id: "different-output" }, history, live), []);
  assert.equal(messageRunSteps(message, { ...history, sequence: "6", steps: [{ ...value, status: "failed" }] }, live)[0].status, "failed");
});

test("独立步骤进入真实子智能体，已有正文和工具不增加重复卡片", async () => {
  const { mergeExecutionSteps } = await import("../.test-build/conversation/agent/lib/conversation-steps.js");
  const raw = [
    { ...block("tool", "tool", null, 1), stepId: "tool-step" },
    { ...block("child", "subagent", "tool", 2), stepId: "child-step" },
    { ...block("text", "text", "child", 4), stepId: "text-step", text: "真实子任务正文" },
  ];
  const blocks = displayMessages(fromSnapshot(snapshot("3", raw)))[0].blocks;
  const records = [step("root", null, "agent"), step("tool-step", "root", "tool", 1), step("child-step", "tool-step", "agent", 2),
    step("text-step", "child-step", "model", 3), { ...step("independent", "child-step", "model", 5), publicSummary: "检查来源后继续" }];
  const merged = mergeExecutionSteps(blocks, records);
  assert.equal(merged.length, 1); assert.equal(merged[0].id, "tool");
  assert.deepEqual(merged[0].blocks[0].blocks.map(value => value.id), ["text", "step:independent"]);
  assert.equal(merged[0].blocks[0].blocks[0].content, "真实子任务正文");
  assert.equal(merged[0].blocks[0].blocks[1].summary, "检查来源后继续");
  assert.equal(blocks[0].blocks[0].blocks.length, 1);
});
