import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const {groupRoomEntries} = loadSource("features/collaboration/lib/web-room-timeline");

function entry(sequence, participantId, kind, taskId = "task") {
  return {id: "entry-" + sequence, sequence, participantId, taskId, kind,
    authorName: participantId ?? "用户", createdAt: "2026-11-10T00:00:00Z",
    text: "内容-" + sequence, attachments: []};
}

function orderedTurns(entries) {
  const turns = groupRoomEntries(entries);
  assert.deepEqual(turns.flat(), entries);
  return turns.map(turn => turn.map(row => row.id));
}

test("委派、协作者成果和恢复后的汇总按 A、B、A 展示", () => {
  const rows = [
    entry(1, null, "user"),
    entry(2, "A", "started"), entry(3, "A", "assigned"), entry(4, "A", "waiting_dependency"),
    entry(5, "B", "started"), entry(6, "B", "work_completed"),
    entry(7, "A", "started"), entry(8, "A", "completed"),
  ];
  assert.deepEqual(orderedTurns(rows), [
    ["entry-1"], ["entry-2", "entry-3", "entry-4"], ["entry-5", "entry-6"], ["entry-7", "entry-8"],
  ]);
});

test("两个成员交错执行时，后来的回复保留在交接后的原位置", () => {
  const rows = [entry(1, "A", "started", "one"), entry(2, "B", "started", "two"),
    entry(3, "A", "completed", "one"), entry(4, "B", "completed", "two")];
  assert.deepEqual(orderedTurns(rows), [["entry-1"], ["entry-2"], ["entry-3"], ["entry-4"]]);
});

test("同一成员的新任务和已结束回复分别展示", () => {
  const rows = [entry(1, "A", "started"), entry(2, "A", "completed"),
    entry(3, "A", "started"), entry(4, "A", "assigned", "other"),
    entry(5, "A", "work_completed", "other"), entry(6, "A", "completed", "other")];
  assert.deepEqual(orderedTurns(rows), [["entry-1", "entry-2"], ["entry-3"],
    ["entry-4", "entry-5"], ["entry-6"]]);
});

test("用户补充与系统提示保留在两个成员发言之间", () => {
  const rows = [entry(1, "A", "started"), entry(2, null, "user"),
    entry(3, "A", "assigned"), entry(4, null, "waiting_approval"),
    entry(5, null, "unprocessed"), entry(6, "A", "completed")];
  assert.deepEqual(orderedTurns(rows), rows.map(row => [row.id]));
});

test("同名成员与缺少成员编号的旧记录不会跨记录合并", () => {
  const rows = [entry(1, "A", "assigned"), entry(2, "B", "work_completed"),
    entry(3, null, "assigned"), entry(4, null, "completed")].map(row => ({...row, authorName: "同名成员"}));
  assert.deepEqual(orderedTurns(rows), rows.map(row => [row.id]));
});

test("新回复只追加到当前发言，不会把已经显示的旧发言移动到成果下", () => {
  const rows = [entry(1, "A", "started"), entry(2, "A", "assigned"), entry(3, "B", "started")];
  const before = groupRoomEntries(rows);
  const after = groupRoomEntries([...rows, entry(4, "B", "work_completed"), entry(5, "A", "started")]);
  assert.deepEqual(after[0], before[0]);
  assert.equal(after[1][0].id, before[1][0].id);
  assert.deepEqual(after[2].map(row => row.id), ["entry-5"]);
});

test("加载更早记录后仍按原顺序显示完整的委派过程", () => {
  const older = [entry(1, "A", "started"), entry(2, "A", "assigned")];
  const current = [entry(3, "A", "waiting_dependency"), entry(4, "B", "started"),
    entry(5, "B", "work_completed"), entry(6, "A", "completed")];
  assert.deepEqual(orderedTurns(Object.freeze([...older, ...current])), [
    ["entry-1", "entry-2", "entry-3"], ["entry-4", "entry-5"], ["entry-6"],
  ]);
  assert.deepEqual(groupRoomEntries([]), []);
});
