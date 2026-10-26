import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource, createTranslator} from "./i18n-test-runtime.mjs";

const {feishuSetupStatus} = loadSource("features/integration/lib/feishu-setup-status");
const job = (stage, kind = "bot", message = "当前步骤未完成，请稍后重试。") => ({stage, kind, message, canRetry: true});

test("只有后台正在处理的阶段显示加载动画，并说明等待方式", () => {
  for (const stage of ["queued", "preparing", "configuring", "publishing", "binding"]) {
    const state = feishuSetupStatus(job(stage));
    assert.equal(state.kind, "processing");
    assert.ok(state.detail);
    if (stage !== "queued") {
      assert.match(state.hint, /后台会继续处理/);
    }
  }
});

test("等待审核和等待本人确认各有说明，不会显示后台处理动画", () => {
  assert.equal(feishuSetupStatus(job("awaiting_scan")).kind, "action");
  const approval = feishuSetupStatus(job("waiting_approval"));
  assert.equal(approval.kind, "waiting");
  assert.match(approval.detail, /自动继续/);
  assert.match(approval.hint, /管理员/);
  const authorization = feishuSetupStatus(job("authorization_required"));
  assert.equal(authorization.kind, "action");
  assert.match(authorization.hint, /授权管理应用/);
});

test("失败保留服务端真实原因，完成与取消分别呈现对应结果", () => {
  const failure = feishuSetupStatus(job("failed", "bot", "飞书拒绝了本次发布。"));
  assert.equal(failure.kind, "error");
  assert.equal(failure.detail, "飞书拒绝了本次发布。");
  assert.match(failure.hint, /重试/);
  assert.equal(feishuSetupStatus({...job("failed"), canRetry: false}).hint, undefined);
  assert.equal(feishuSetupStatus(job("completed", "management")).title, "管理应用已连接");
  assert.equal(feishuSetupStatus(job("completed")).title, "机器人创建完成");
  assert.equal(feishuSetupStatus(job("cancelled")).kind, "stopped");
  assert.equal(feishuSetupStatus(job("expired")).kind, "stopped");
});

test("所有阶段的固定状态文案都有英文", () => {
  const english = createTranslator("en");
  for (const kind of ["management", "bot"]) {
    for (const stage of ["queued", "awaiting_scan", "preparing", "configuring", "publishing", "waiting_approval", "binding",
      "completed", "authorization_required", "failed", "registration_unknown", "expired", "cancelled"]) {
      const state = feishuSetupStatus(job(stage, kind));
      for (const value of [state.title, state.detail, state.hint].filter(Boolean)) {
        assert.doesNotMatch(english(value), /[\u3400-\u9fff]/, value);
      }
    }
  }
});
