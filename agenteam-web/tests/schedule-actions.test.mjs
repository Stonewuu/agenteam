import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

const source = readFileSync(new URL("../src/features/schedule/lib/schedule-form.ts", import.meta.url), "utf8");
const code = ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022}}).outputText;
const exported = {};
vm.runInNewContext(code, {exports: exported});
const normalized = value => JSON.parse(JSON.stringify(value));

test("切换到通知只提交明确接收人和渠道，不混入员工字段或模型重试次数", () => {
  const draft = exported.initialScheduleForm(null, "Asia/Shanghai", {hireId: "hire-one"});
  Object.assign(draft, {name: " 提醒 ", actionType: "notification.send", maxRetries: 2, inputText: "之前的智能体内容", title: " 开会 ", body: " 准备资料 ",
    recipients: [{userId: "person-one", name: "成员", channels: [{connectionId: "app-one", name: "飞书", providerCode: "feishu"}]}]});
  const request = normalized(exported.scheduleFormRequest(draft));
  assert.equal(request.maxRetries, 0);
  assert.equal(request.name, "提醒");
  assert.equal("hireId" in request, false);
  assert.equal("inputText" in request, false);
  assert.deepEqual(request.action, {type: "notification.send", schemaVersion: 1, config: {title: "开会", body: "准备资料",
    recipients: [{userId: "person-one", connectionIds: ["app-one"]}]}});
});

test("智能体操作保留固定版本和重试次数，编辑通知时保留原成员及渠道", () => {
  const draft = exported.initialScheduleForm(null, "UTC", {hireId: "hire-one"});
  Object.assign(draft, {name: "整理", agentVersionId: "version-fixed", inputText: " 原任务 ", maxRetries: 2,
    title: "之前通知", recipients: [{userId: "person-one", channels: []}]});
  const request = normalized(exported.scheduleFormRequest(draft));
  assert.equal(request.maxRetries, 2);
  assert.deepEqual(request.action.config, {hireId: "hire-one", agentVersionId: "version-fixed", inputText: "原任务"});
  const initial = {...request, localTime: "09:15:00", action: {type: "notification.send", config: {title: "原通知", body: "原正文"},
    recipients: [{userId: "person-one", name: "原成员", channels: [{connectionId: "app-one", name: "飞书", providerCode: "feishu"}]}]}};
  const edited = exported.initialScheduleForm(initial, "UTC");
  assert.equal(edited.localTime, "09:15");
  assert.equal(edited.actionType, "notification.send");
  assert.equal(edited.title, "原通知");
  assert.equal(edited.recipients[0].channels[0].connectionId, "app-one");
});
