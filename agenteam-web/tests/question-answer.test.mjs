import assert from "node:assert/strict";
import {test} from "node:test";
import {createRequire} from "node:module";

const load = createRequire(import.meta.url);
const {questionAnswer, customQuestionChoice} = load("../.test-build/conversation/agent/lib/question-answer.js");
const question = {status: "pending", options: [{id: "option_0", label: "完整复盘", recommended: true}, {id: "option_1", label: "快速结论"}]};
const empty = {choice: null, customText: "", note: "", noteOpen: false};

test("推荐选项仍需用户主动选择，空白回答不能提交", () => {
  assert.equal(questionAnswer(question, empty), null);
  assert.equal(questionAnswer(question, {...empty, choice: customQuestionChoice, customText: " \n "}), null);
  assert.equal(questionAnswer({...question, options: []}, {...empty, customText: " \n "}), null);
  assert.equal(questionAnswer(question, {...empty, choice: "removed_option", note: "补充", noteOpen: true}), null);
});

test("改选推荐选项后不发送另一种填写方式的草稿", () => {
  assert.deepEqual(questionAnswer(question, {...empty, choice: "option_0", customText: "保留的自由回答"}),
    {optionId: "option_0", text: null});
  assert.deepEqual(questionAnswer(question, {...empty, choice: "option_1", customText: "保留的自由回答", noteOpen: true, note: " 请控制在一页内 "}),
    {optionId: "option_1", text: "请控制在一页内"});
});

test("取消补充后只提交选择，不发送隐藏的说明", () => {
  assert.deepEqual(questionAnswer(question, {...empty, choice: "option_0", noteOpen: false, note: "隐藏的说明"}),
    {optionId: "option_0", text: null});
});

test("自己填写和没有推荐选项的问题都支持完整文字回答", () => {
  const draft = {...empty, choice: customQuestionChoice, customText: " 先列结论\n再列计划 ", noteOpen: true, note: "不应混入的选项说明"};
  assert.deepEqual(questionAnswer(question, draft), {optionId: null, text: "先列结论\n再列计划"});
  assert.deepEqual(questionAnswer({...question, options: []}, {...draft, choice: null}), {optionId: null, text: "先列结论\n再列计划"});
});

test("已回答、结束或过期的提问都不能再提交草稿", () => {
  for (const status of ["answered", "cancelled", "expired"]) {
    assert.equal(questionAnswer({...question, status}, {...empty, choice: "option_0"}), null);
    assert.equal(questionAnswer({...question, status}, {...empty, choice: customQuestionChoice, customText: "回答"}), null);
  }
});
