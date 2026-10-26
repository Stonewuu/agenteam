import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const {feishuSetupProgress} = loadSource("features/integration/lib/feishu-setup-progress");
const job = (kind, stage, connectionId = null) => ({kind, stage, connectionId});

test("企业接入在配置发布与本人授权都完成后才标记完成", () => {
  assert.equal(feishuSetupProgress(null, null, "enterprise").step, "connect");
  assert.equal(feishuSetupProgress(job("enterprise", "publishing", "app"), {authorized: true}, "enterprise").step, "configure");
  assert.deepEqual(feishuSetupProgress(job("enterprise", "completed", "app"), {authorized: false}, "enterprise"),
    {step: "authorize", complete: false, attention: false});
  assert.deepEqual(feishuSetupProgress(null, {authorized: true}, "enterprise"), {step: "complete", complete: true, attention: false});
  assert.equal(feishuSetupProgress(job("enterprise", "completed", "app"), {authorized: true}).step, "create");
});

test("首次接入和已有授权分别从实际所需步骤开始", () => {
  assert.equal(feishuSetupProgress().step, "connect");
  assert.equal(feishuSetupProgress(null, {authorized: false}).step, "authorize");
  assert.equal(feishuSetupProgress(null, {authorized: true}).step, "create");
});

test("管理应用发布完成只进入授权步骤，不表示机器人已经完成", () => {
  for (const stage of ["queued", "awaiting_scan", "configuring", "publishing", "waiting_approval"]) {
    assert.equal(feishuSetupProgress(job("management", stage)).step, "connect");
  }
  assert.deepEqual(feishuSetupProgress(job("management", "completed", "manager")),
    {step: "authorize", complete: false, attention: false});
});

test("岗位机器人需在发布生效并完成绑定后才到达完成", () => {
  for (const stage of ["queued", "awaiting_scan", "preparing"]) {
    assert.equal(feishuSetupProgress(job("bot", stage)).step, "create");
  }
  for (const stage of ["configuring", "publishing", "waiting_approval", "binding"]) {
    assert.deepEqual(feishuSetupProgress(job("bot", stage, "bot-connection")),
      {step: "configure", complete: false, attention: false});
  }
  assert.deepEqual(feishuSetupProgress(job("bot", "completed", "bot-connection")),
    {step: "complete", complete: true, attention: false});
});

test("失败、结果未知和重新授权保留未完成状态并定位需要处理的步骤", () => {
  assert.deepEqual(feishuSetupProgress(job("bot", "failed")), {step: "create", complete: false, attention: true});
  assert.deepEqual(feishuSetupProgress(job("bot", "failed", "created-app")), {step: "configure", complete: false, attention: true});
  assert.deepEqual(feishuSetupProgress(job("bot", "authorization_required", "created-app")),
    {step: "authorize", complete: false, attention: true});
  for (const stage of ["cancelled", "expired", "registration_unknown"]) {
    assert.equal(feishuSetupProgress(job("bot", stage)).complete, false);
  }
});
