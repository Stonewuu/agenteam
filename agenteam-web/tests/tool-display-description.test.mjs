import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource, createTranslator} from "./i18n-test-runtime.mjs";

const {toolDisplayDescription} = loadSource("features/plugin/lib/tool-display-name");

test("内置工具页面说明不输出模型指令，英文使用相同业务说明", () => {
  const tool = {name: "todo_assignees", description: "查询当前待办可以选择的真实负责人，不要猜测成员编号。"};
  const original = {...tool};
  assert.equal(toolDisplayDescription(tool, "todo_management"), "查找可负责此待办的企业成员。");
  assert.equal(toolDisplayDescription(tool, "待办管理", createTranslator("en")), "Find organization members who can own this to-do.");
  assert.deepEqual(tool, original);
});

test("外部同名工具和未登记内置工具保留原有说明", () => {
  const tool = {name: "todo_assignees", description: "用户自行填写的工具说明：真实验证。"};
  for (const source of [undefined, null, "custom_plugin", "schedule_management"]) {
    assert.equal(toolDisplayDescription(tool, source), tool.description);
  }
  assert.equal(toolDisplayDescription({...tool, name: "new_tool"}, "todo_management"), tool.description);
});

test("协作工具和文件工具以用途说明，不改变工具标识", () => {
  const tool = {name: "collab_read_context", description: "读取 inputRevision 并绑定 workId"};
  assert.equal(toolDisplayDescription(tool, "group_collaboration"), "查看任务要求、补充信息和工作进展。");
  assert.equal(tool.name, "collab_read_context");
  assert.equal(toolDisplayDescription({name: "export_file", description: "内部执行说明"}, "workspace_files"), "将工作文件保存为可下载的附件。");
});
