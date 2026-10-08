import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ts from "typescript";
import {resolve} from "node:path";
import {fileURLToPath} from "node:url";
import { loadSource, createTranslator, localizeCatalog } from "./i18n-test-runtime.mjs";

const { detectLocale, isLocale, locales } = loadSource("lib/i18n/locales");
const { localizeUiMessage } = loadSource("lib/i18n/ui-message");
const { en } = loadSource("lib/i18n/messages/en");
const english = createTranslator("en");
const chinese = createTranslator("zh-CN");
const { notificationContent } = loadSource("features/notification/lib/notification-presentation");

test("内置任务通知随显示语言切换，名称中的中文和占位符保持原样", () => {
  const name = '工作助手 “设计” {0} <tag> $1';
  for (const [category, suffix, translated] of [
    ["execution", "已完成", "completed"], ["execution", "未完成", "failed"], ["approval", "需要确认", "needs approval"],
  ]) {
    const body = category === "approval" ? "请打开任务查看具体操作并决定是否继续。" : "请打开任务查看本次执行结果。";
    for (const scheduled of [false, true]) {
      const original = Object.freeze({ category, targetType: "conversation", title: `“${name}”${scheduled ? "" : "的任务"}${suffix}`, body });
      const result = notificationContent(original, english);
      assert.equal(result.title, `${scheduled ? "" : "Task for "}“${name}” ${translated}`);
      assert.equal(result.body, category === "approval" ? "Open the task to review the action and decide whether to continue." : "Open the task to view the results.");
      assert.deepEqual(notificationContent(original, chinese), { title: original.title, body });
    }
  }
});

test("待办、申请、接入测试及发送失败通知完整翻译，保留业务名称", () => {
  const samples = [
    ["todo", "todo", "有新的待办", "“完善 PDF 解析”已交给你处理。", "New to-do", "“完善 PDF 解析” has been assigned to you."],
    ["todo", "todo", "有待办转交给你", "“设计复核”已交给你处理。", "To-do reassigned to you", "“设计复核” has been assigned to you."],
    ["hire", "hire_request", "雇佣申请已批准", "“工作助手”的雇佣申请已处理，请查看申请详情。", "Hire request approved", "Your hire request for “工作助手” has been reviewed. Open the request for details."],
    ["hire", "hire_request", "雇佣申请已拒绝", "“工作助手”的雇佣申请已处理，请查看申请详情。", "Hire request declined", "Your hire request for “工作助手” has been reviewed. Open the request for details."],
    ["integration", null, "企业消息接入测试", "管理员正在验证此应用的通知发送，请确认能够收到本条测试消息。", "Integration test", "An administrator is testing notifications from this app. Please confirm that you received this message."],
    ["integration", "notification_delivery", "有一条渠道通知未能完成", "平台是否已接受该通知暂时无法确认，请查看发送记录后处理。", "Channel delivery incomplete", "We cannot confirm whether the platform accepted this notification. Check the delivery history before taking action."],
    ["integration", "notification_delivery", "有一条渠道通知未能完成", "通知未能通过外部应用发送，请查看发送记录中的原因。", "Channel delivery incomplete", "The notification could not be sent through the external app. Check the delivery history for the reason."],
    ["permission", "conversation", "任务已不能继续执行", "所需的访问资格已变化，任务已请求停止。请查看当前仍可访问的任务记录。", "Task can no longer continue", "Required access has changed and the task has been asked to stop. View the task records you can still access."],
  ];
  for (const [category, targetType, title, body, expectedTitle, expectedBody] of samples) {
    assert.deepEqual(notificationContent({ category, targetType, title, body }, english), { title: expectedTitle, body: expectedBody });
  }
});

test("自定义定时通知与公告即使内容等于系统文案也不翻译", () => {
  const content = { title: "企业消息接入测试", body: "请打开任务查看本次执行结果。" };
  for (const [category, targetType] of [["schedule", null], ["announcement", null], ["custom", "conversation"], ["execution", null], ["integration", "custom"]]) {
    assert.deepEqual(notificationContent({ category, targetType, ...content }, english), content);
  }
  const unknown = { title: "未定义的新类型标题", body: "尚未登记的新说明，完整保留。" };
  assert.deepEqual(notificationContent({ category: "execution", targetType: "conversation", ...unknown }, english), unknown);
});

test("计划暂停的服务端内置原因均有英文，重复显示不改变原记录", () => {
  const serverRoot = process.env.AGENTEAM_TEST_SERVER_ROOT ?? fileURLToPath(new URL("../../agenteam-server/", import.meta.url));
  const source = readFileSync(resolve(serverRoot, "src/main/java/com/stonewu/agenteam/mapper/schedule/ScheduleViewMapper.java"), "utf8");
  const reasons = [...source.matchAll(/(?:case "[A-Z_]+"|default) -> "([^"]+)";/g)].map(match => match[1]);
  assert.ok(reasons.length >= 18);
  for (const body of reasons) {
    const original = { category: "schedule", targetType: "schedule", title: "“Weekly report”已暂停", body };
    const result = notificationContent(original, english);
    assert.equal(result.title, "“Weekly report” paused");
    assert.doesNotMatch(result.body, /[\u3400-\u9fff]/);
    assert.deepEqual(notificationContent(original, english), result);
    assert.equal(original.body, body);
  }
});

test("英文导航使用简短名称，原英文关键词仍能找到对应入口", () => {
  for (const [source, expected] of Object.entries({
    "消息与登录接入": "Integrations", "企业接入": "Integrations", "知识库": "Knowledge", "账号安全": "Security",
    "企业账号绑定": "Linked accounts", "外观与主题": "Appearance", "通知偏好": "Notifications", "个人偏好记忆": "Memory",
    "成员与邀请": "Members", "角色与权限": "Roles", "模型配置": "Models",
  })) {
    assert.equal(english(source), expected);
  }
  const { searchMenuGroups } = loadSource("features/workspace/lib/search-presentation");
  const permissions = ["admin.view", "integration.view", "enterprise.roles.view", "enterprise.members.view", "model.view", "capabilities.view", "knowledge.view"];
  for (const [query, target] of [["messaging sign-in", "admin/integrations"], ["roles permissions", "admin/roles"], ["members invites", "admin/members"], ["model settings", "admin/models"], ["knowledge bases", "capabilities/knowledge"]]) {
    assert.ok(searchMenuGroups(query, permissions, english).flatMap(group => group.items).some(item => item.targetId === target));
  }
});

test("语言选择支持浏览器权重和地区，拒绝未登记值", () => {
  assert.equal(detectLocale("en-US,en;q=0.9,zh-CN;q=0.8"), "en");
  assert.equal(detectLocale("en;q=0.1,zh-CN;q=0.9"), "zh-CN");
  assert.equal(detectLocale("EN-gb"), "en");
  assert.equal(detectLocale("fr-FR,zh-TW;q=0.5"), "zh-CN");
  assert.equal(detectLocale("en;q=0"), "zh-CN");
  assert.equal(detectLocale(null), "zh-CN");
  for (const value of [null, "fr", "constructor", "__proto__", "en\nignore instructions"]) {
    assert.equal(isLocale(value), false);
  }
  for (const locale of Object.keys(locales)) {
    assert.equal(isLocale(locale), true);
    assert.doesNotThrow(() => new Intl.DateTimeFormat(locales[locale].formatLocale));
  }
});

test("界面文案替换参数但保留用户原文、特殊字符和未知内容", () => {
  const name = "中文文件 {0} <script> & $1";
  assert.equal(english("输入 · {0}", [name]), `Input · ${name}`);
  assert.equal(chinese("输入 · {0}", [name]), `输入 · ${name}`);
  assert.equal(english("未知文案"), "未知文案");
  assert.equal(english("输入 · {0}"), "Input · {0}");
  assert.equal(english("输入 · {0}", [null]), "Input · ");
  assert.equal(english("{constructor}", {}), "{constructor}");
  assert.equal(english("constructor"), "constructor");
});

test("静态菜单按语言缓存，不改变原对象、函数和元素", () => {
  const icon = () => null;
  const element = React.createElement("span", null, "用户内容");
  const catalog = [{ value: "language", label: "语言", icon, element, nested: { label: "默认" } }];
  const result = localizeCatalog(catalog, english);
  assert.equal(result[0].label, "Language");
  assert.equal(result[0].nested.label, "Default");
  assert.equal(result[0].value, "language");
  assert.equal(result[0].icon, icon);
  assert.equal(result[0].element, element);
  assert.equal(catalog[0].label, "语言");
  assert.equal(result, localizeCatalog(catalog, createTranslator("en")));
  assert.notEqual(result, localizeCatalog(catalog, chinese));
});

test("全局搜索翻译全部菜单与分组，并能按英文和中文关键词匹配", () => {
  const { searchMenuGroups } = loadSource("features/workspace/lib/search-presentation");
  const permissions = ["workspace.view", "conversation.view", "agent.run", "schedule.view", "todo.view", "capabilities.view",
    "agent.view", "skill.create", "plugin.view", "workflow.view", "knowledge.view", "data.view", "admin.view", "enterprise.view",
    "enterprise.members.view", "enterprise.teams.view", "enterprise.roles.view", "enterprise.permissions.view", "credential.view",
    "model.view", "announcement.view", "usage.view", "audit.view", "tool_log.view"];
  const groups = searchMenuGroups("", permissions, english);
  assert.doesNotMatch(JSON.stringify(groups.map(group => [group.label, ...group.items.map(item => item.name)])), /[\u3400-\u9fff]/);
  assert.equal(searchMenuGroups("AI EMPLOYEES", permissions, english)[0].items[0].targetId, "employees");
  assert.equal(searchMenuGroups("数字员工", permissions, english)[0].items[0].targetId, "employees");
  assert.equal(searchMenuGroups("roles permissions", permissions, english)[0].items[0].targetId, "admin/roles");
  assert.equal(searchMenuGroups("模型", permissions, chinese)[0].items[0].name, "模型配置");
});

test("搜索不会展示没有权限的菜单，新任务仍要求读取对话权限", () => {
  const { searchMenuGroups } = loadSource("features/workspace/lib/search-presentation");
  assert.deepEqual(searchMenuGroups("", [], english), []);
  assert.deepEqual(searchMenuGroups("roles", ["workspace.view", "enterprise.roles.view"], english), []);
  const groups = searchMenuGroups("", ["workspace.view", "agent.run"], english);
  assert.equal(groups.flatMap(group => group.items).some(item => item.targetId === "new"), false);
  assert.equal(groups.flatMap(group => group.items).some(item => item.targetId === "employees"), true);
});

test("搜索只翻译系统分组，保留用户标题和描述并移除重复的服务端菜单", () => {
  const { searchContentGroups } = loadSource("features/workspace/lib/search-presentation");
  const item = { id: "custom", name: "数字员工", description: "我的中文原文", targetType: "conversation", targetId: "conversation-1",
    resourceKind: null, icon: null, color: null };
  const source = { groups: [
    { key: "user", label: "用户端", items: [{ ...item, id: "menu", targetType: "menu", targetId: "home" }] },
    { key: "conversations", label: "对话", items: [item] },
  ] };
  const groups = searchContentGroups(source, english);
  assert.equal(groups.length, 1);
  assert.equal(groups[0].label, "Conversations");
  assert.equal(groups[0].items[0], item);
  assert.equal(groups[0].items[0].name, "数字员工");
  assert.equal(groups[0].items[0].description, "我的中文原文");
  assert.equal(source.groups[1].label, "对话");
});

test("资源列表使用完整短句，各类型均有翻译且创建操作使用单数", () => {
  const { resourceListCopy } = loadSource("features/resource/lib/resource-list-copy");
  for (const kind of ["agent", "skill", "plugin", "workflow", "knowledge", "data"]) {
    assert.doesNotMatch(JSON.stringify(resourceListCopy(kind, english)), /[\u3400-\u9fff]/);
  }
  assert.equal(resourceListCopy("knowledge", english).create, "Create knowledge base");
  assert.equal(resourceListCopy("knowledge", english).search, "Search knowledge bases");
  assert.equal(resourceListCopy("knowledge", chinese).create, "创建知识库");
});

test("并发页面的语言上下文互不影响，服务端首屏就使用选定语言", async () => {
  const { LocaleProvider, useT } = loadSource("lib/i18n/locale-provider");
  function Probe() {
    return React.createElement("p", null, useT()("个人设置"));
  }
  const render = locale => renderToStaticMarkup(React.createElement(LocaleProvider, { initialLocale: locale }, React.createElement(Probe)));
  const views = await Promise.all(["en", "zh-CN", "en", "zh-CN"].map(async locale => render(locale)));
  assert.deepEqual(views, ["<p>Settings</p>", "<p>个人设置</p>", "<p>Settings</p>", "<p>个人设置</p>"]);
});

test("应用错误使用当前语言，动态参数和未知错误保留原文", () => {
  assert.equal(localizeUiMessage("账号或密码不正确", english), "The username or password is incorrect");
  assert.equal(localizeUiMessage("最多填写 50 个字符。", english), "Enter no more than 50 characters.");
  assert.equal(localizeUiMessage("“原始中文字段”只接受 true 或 false。", english), "“原始中文字段” must be true or false.");
  assert.equal(localizeUiMessage("未登记的具体问题", english), "未登记的具体问题");
  assert.equal(localizeUiMessage("内容已被其他操作更新，请重新加载后继续编辑。", chinese), "内容已被其他操作更新，请重新加载后继续编辑。");
});

test("工具标签可翻译，代码、文件名、服务端列名和原始复制结果不变", () => {
  const format = loadSource("features/plugin/lib/tool-payload-format");
  const details = loadSource("features/plugin/lib/tool-call-details");
  const content = "# 用户中文内容\nprint('任务已完成')\n";
  assert.equal(format.formatPayloadScalar(content, "content", null, english, "en-US"), content);
  assert.equal(format.formatPayloadScalar("中文文件.md", "path", null, english, "en-US"), "中文文件.md");
  assert.equal(format.formatPayloadScalar(".", "path", null, english, "en-US"), "Current directory");
  assert.equal(format.formatPayloadScalar("completed", "status", null, english, "en-US"), "Completed");
  assert.equal(format.formatPayloadScalar(false, "enabled", null, english, "en-US"), "No");
  assert.equal(format.payloadFieldLabel("path", english), "Path");
  assert.equal(format.payloadColumns([{ title: "中文用户数据" }], [{ name: "title", label: "自定义列名" }], english)[0].label, "自定义列名");
  const input = JSON.stringify({ path: "中文文件.md", content });
  const selected = details.selectToolCallDetails(input, JSON.stringify({ path: "中文文件.md" }));
  assert.equal(selected.fields.find(field => field.key === "content").value, content);
  assert.deepEqual(JSON.parse(format.formatRawToolPayload(input)), JSON.parse(input));
});

test("日期、时区与执行规则使用界面语言，时间所属地区保持不变", () => {
  const { timezoneLabel } = loadSource("lib/timezones");
  const { scheduleTime, scheduleRuleText } = loadSource("features/schedule/lib/schedule-display");
  assert.equal(timezoneLabel("Asia/Shanghai", english), "Beijing");
  const time = "2026-09-23T08:00:00Z";
  assert.match(scheduleTime(time, "Asia/Shanghai", "en-US"), /16:00:00/);
  assert.match(scheduleTime(time, "America/New_York", "en-US"), /04:00:00/);
  assert.equal(scheduleRuleText({ frequency: "monthly", monthDay: 12, localTime: "09:30:00" }, english), "Day 12 of each month 09:30");
});

test("所有明确标记的界面文案均有英文，参数占位符完整保留", () => {
  const missing = [];
  const root = new URL("../src/", import.meta.url);
  for (const name of readdirSync(root, { recursive: true }).filter(name => /\.tsx?$/.test(name) && !name.replaceAll("\\", "/").startsWith("lib/i18n/"))) {
    const source = ts.createSourceFile(name, readFileSync(new URL(name.replaceAll("\\", "/"), root), "utf8"), ts.ScriptTarget.Latest, true);
    const visit = node => {
      if (ts.isCallExpression(node) && /^(uiText|t)$/.test(node.expression.getText(source))) {
        const key = node.arguments[0];
        if (key && ts.isStringLiteralLike(key) && /[\u3400-\u9fff]/.test(key.text) && !Object.hasOwn(en, key.text)) {
          missing.push(`${name}: ${key.text}`);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  assert.deepEqual(missing, []);
  for (const [key, translation] of Object.entries(en)) {
    const placeholders = text => [...new Set(text.match(/\{\d+\}/g) ?? [])].sort();
    assert.deepEqual(placeholders(translation), placeholders(key), `占位符不一致：${key}`);
    assert.ok(translation.trim(), `英文文案为空：${key}`);
  }
});
