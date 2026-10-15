import assert from "node:assert/strict";
import {test} from "node:test";
import {loadSource} from "./i18n-test-runtime.mjs";

const {emptyListState, listStateKey, readListState, changeListCriteria} = loadSource("features/collaboration/lib/collaboration-list-state");

test("列表条件按账号和企业分别保存", () => {
  assert.notEqual(listStateKey("one", "user"), listStateKey("two", "user"));
  assert.notEqual(listStateKey("one", "user"), listStateKey("one", "other"));
  assert.notEqual(listStateKey("one:two", "user"), listStateKey("one", "two:user"));
});

test("从详情返回可以恢复搜索、状态、分页历史与阅读位置", () => {
  const state = {query:"架构设计", status:"completed", cursor:"page-three", previous:[null,"page-two"], scrollTop:640};
  assert.deepEqual(readListState(JSON.parse(JSON.stringify(state))), state);
});

test("修改筛选必须回到第一页，不能拿旧分页位置查询新范围", () => {
  const state = {query:"旧任务", status:"completed", cursor:"old-page", previous:[null], scrollTop:640};
  assert.deepEqual(changeListCriteria(state,{query:"新任务"}), {query:"新任务",status:"completed",cursor:null,previous:[],scrollTop:0});
  assert.equal(state.cursor,"old-page");
});

test("过期格式、未知状态与损坏的分页保存值不会影响重新打开列表", () => {
  assert.deepEqual(readListState(null),emptyListState);
  assert.deepEqual(readListState({...emptyListState,status:"not-a-status"}),emptyListState);
  assert.deepEqual(readListState({...emptyListState,previous:[123]}),emptyListState);
  assert.deepEqual(readListState({...emptyListState,cursor:"x".repeat(2049)}),emptyListState);
  assert.equal(readListState({...emptyListState,scrollTop:-1}).scrollTop,0);
});
