import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {test} from "node:test";
import vm from "node:vm";
import ts from "typescript";

const exports = {};
const source = readFileSync(new URL("../src/features/chat/api/save-chat-bot.ts", import.meta.url), "utf8");
vm.runInNewContext(ts.transpileModule(source, {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText, {exports});
const {saveChatBot} = exports;

test("机器人已创建但启用失败，重试只启用原机器人", async () => {
  const calls = [];
  const progress = {current: null};
  let fail = true;
  const mutation = {run: async (path, input) => {
    calls.push({path, input});
    if (input.method === "POST") {
      return {id: "created-bot", revision: 1};
    }
    if (fail) {
      fail = false;
      throw new Error("模拟启用失败");
    }
    return {id: "created-bot", revision: 2, ...input.body};
  }};
  await assert.rejects(saveChatBot(mutation, "/chat", null, progress, {agentId: "agent"}, null, () => {}));
  assert.equal(progress.current.id, "created-bot");
  await saveChatBot(mutation, "/chat", null, progress, {agentId: "agent"}, null, () => {});
  assert.equal(calls.filter(call => call.input.method === "POST").length, 1);
  assert.equal(calls.at(-1).path, "/chat/bots/created-bot");
  assert.equal(calls.at(-1).input.body.status, "enabled");
  assert.equal(calls.at(-1).input.body.chatEnabled, true);
});

test("回调材料保存后保留新版本，编辑停用机器人不会意外启用", async () => {
  const initial = {id: "existing", revision: 3, status: "disabled", chatEnabled: false};
  const progress = {current: null};
  const calls = [];
  let cleared = false;
  const mutation = {run: async (path, input) => {
    calls.push({path, input});
    return {...initial, revision: input.method === "POST" ? 4 : 5, callbackConfigured: true};
  }};
  await saveChatBot(mutation, "/chat", initial, progress, {agentId: "agent"},
    {verificationToken: "synthetic-token", encryptKey: "synthetic-key"}, () => {
      cleared = true;
    });
  assert.equal(cleared, true);
  assert.equal(calls.at(-1).input.body.expectedRevision, 4);
  assert.equal(calls.at(-1).input.body.status, "disabled");
  assert.equal(calls.at(-1).input.body.chatEnabled, false);
  assert.equal(progress.current.revision, 5);
});
