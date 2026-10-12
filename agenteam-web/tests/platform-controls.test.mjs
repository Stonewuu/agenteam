import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {execFileSync} from "node:child_process";
import {Linter} from "eslint";
import ts from "typescript";
import {platformControlsRule} from "../eslint.config.mjs";

const source = readFileSync(new URL("../src/components/ui/date-time-value.ts", import.meta.url), "utf8");
const code = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022}}).outputText;
const moduleUrl = `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`;
const {parseDate, dateValue, dateWithinBounds, normalizeDateTime, currentDateTime, dateTimeAllowed} = await import(moduleUrl);
const rangeSource = readFileSync(new URL("../src/components/ui/date-range-value.ts", import.meta.url), "utf8");
const rangeCode = ts.transpileModule(rangeSource, {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022}}).outputText
  .replace('from "./date-time-value"', `from "${moduleUrl}"`);
const rangeModuleUrl = `data:text/javascript;base64,${Buffer.from(rangeCode).toString("base64")}`;
const {orderedDateRange, dateRangeDays, dateRangeAllowed, dateRangePreset} = await import(rangeModuleUrl);

test("日期输入支持直接输入并严格校验闰日、月份和时间", () => {
  assert.equal(normalizeDateTime("2024/2/29", "date"), "2024-02-29");
  assert.equal(normalizeDateTime("2026-02-29", "date"), null);
  assert.equal(normalizeDateTime("2026-13-01", "date"), null);
  assert.equal(normalizeDateTime("0000-01-01", "date"), null);
  assert.equal(dateValue(parseDate("0099-12-31")), "0099-12-31");
  assert.equal(normalizeDateTime("9:05", "time"), "09:05");
  assert.equal(normalizeDateTime("24:00", "time"), null);
  assert.equal(normalizeDateTime("09:60", "time"), null);
  assert.equal(normalizeDateTime("09:30:60", "time"), null);
  assert.equal(normalizeDateTime("09:30:06.250", "time"), "09:30:06.250");
});

test("日期时间不丢失秒和小数，也不接受多余内容", () => {
  assert.equal(normalizeDateTime("2026-10-12 09:30", "datetime-local"), "2026-10-12T09:30");
  assert.equal(normalizeDateTime("2026-10-12T09:30:06.250", "datetime-local"), "2026-10-12T09:30:06.250");
  assert.equal(normalizeDateTime("2026-10-12 09:30 extra", "datetime-local"), null);
  assert.equal(normalizeDateTime("2026-10-12", "datetime-local"), null);
  assert.equal(normalizeDateTime("2026-10-12T09:30Z", "datetime-local"), null);
  for (const type of ["date", "time", "datetime-local"]) {
    assert.equal(normalizeDateTime("", type), "");
  }
  assert.equal(currentDateTime("datetime-local", true, new Date(2026, 9, 12, 9, 5, 6)), "2026-10-12T09:05:06");
});

test("正负时区和夏令时切换不改变日历选择的日期", () => {
  for (const zone of ["Asia/Shanghai", "America/Los_Angeles", "Pacific/Honolulu"]) {
    const check = `import {dateValue,parseDate} from ${JSON.stringify(moduleUrl)}; console.log(JSON.stringify(["2026-03-08","2026-11-01","2024-02-29","0099-12-31"].map(value=>dateValue(parseDate(value)))));`;
    const result = execFileSync(process.execPath, ["--input-type=module", "-e", check], {encoding: "utf8", windowsHide: true, env: {...process.env, TZ: zone}});
    assert.deepEqual(JSON.parse(result), ["2026-03-08", "2026-11-01", "2024-02-29", "0099-12-31"]);
  }
});

test("范围、步长及跨午夜时间限制保留原表单约束", () => {
  const dates = {type: "date", min: "2026-10-01", max: "2026-10-31", step: 2};
  assert.equal(dateTimeAllowed("2026-10-01", dates), true);
  assert.equal(dateTimeAllowed("2026-10-02", dates), false);
  assert.equal(dateTimeAllowed("2026-10-31", dates), true);
  assert.equal(dateTimeAllowed("2026-11-01", dates), false);
  const times = {type: "time", min: "22:00", max: "02:00", step: 900};
  assert.equal(dateTimeAllowed("23:15", times), true);
  assert.equal(dateTimeAllowed("01:30", times), true);
  assert.equal(dateTimeAllowed("12:00", times), false);
  assert.equal(dateTimeAllowed("23:16", times), false);
  assert.equal(dateTimeAllowed("23:16:12.250", {...times, step: "any"}), true);
  const dateTimes = {type: "datetime-local", min: "2026-10-12T09:30", max: "2026-10-12T10:00", step: 60};
  assert.equal(dateTimeAllowed("2026-10-12T09:45", dateTimes), true);
  assert.equal(dateTimeAllowed("2026-10-12T09:29", dateTimes), false);
  assert.equal(dateTimeAllowed("2026-10-12T09:45:10", dateTimes), false);
  assert.equal(dateTimeAllowed("", dates), false);
});

test("日期时间的最早与最晚当天仍然可以在日历选择", () => {
  const min = parseDate("2026-10-12");
  const max = parseDate("2026-10-15");
  assert.equal(dateWithinBounds(new Date(2026, 9, 12, 0), min, max), true);
  assert.equal(dateWithinBounds(new Date(2026, 9, 15, 23, 59), min, max), true);
  assert.equal(dateWithinBounds(new Date(2026, 9, 11, 23, 59), min, max), false);
  assert.equal(dateWithinBounds(new Date(2026, 9, 16, 0), min, max), false);
});

test("日期范围支持同一天、跨月、倒序点选，并拒绝不完整或超限范围", () => {
  assert.deepEqual(orderedDateRange(parseDate("2026-11-02"), parseDate("2026-10-30")), {from: "2026-10-30", to: "2026-11-02"});
  assert.equal(dateRangeDays({from: "2026-10-12", to: "2026-10-12"}), 1);
  assert.equal(dateRangeDays({from: "2024-02-28", to: "2024-03-01"}), 3);
  assert.equal(dateRangeAllowed({from: "2026-10-12", to: ""}, 93), false);
  assert.equal(dateRangeAllowed({from: "2026-10-12", to: "2026-10-11"}, 93), false);
  assert.equal(dateRangeAllowed({from: "2026-01-01", to: "2026-04-03"}, 93), true);
  assert.equal(dateRangeAllowed({from: "2026-01-01", to: "2026-04-04"}, 93), false);
});

test("常用日期范围包含当天并正确跨年、跨闰日", () => {
  const now = new Date(2026, 0, 3, 0, 10);
  assert.deepEqual(dateRangePreset("today", now), {from: "2026-01-03", to: "2026-01-03"});
  assert.deepEqual(dateRangePreset("week", now), {from: "2025-12-28", to: "2026-01-03"});
  assert.deepEqual(dateRangePreset("month", now), {from: "2025-12-05", to: "2026-01-03"});
  assert.deepEqual(dateRangePreset("currentMonth", now), {from: "2026-01-01", to: "2026-01-03"});
  assert.equal(dateRangeDays(dateRangePreset("week", new Date(2024, 2, 1))), 7);
});

test("范围天数不受夏令时影响，查询上限与接口的连续小时限制一致", () => {
  const check = `import {dateRangeDays,dateRangeAllowed} from ${JSON.stringify(rangeModuleUrl)}; console.log(JSON.stringify([dateRangeDays({from:"2026-03-07",to:"2026-03-09"}),dateRangeAllowed({from:"2026-08-01",to:"2026-11-01"},93),dateRangeAllowed({from:"2026-08-02",to:"2026-11-01"},93)]));`;
  const result = execFileSync(process.execPath, ["--input-type=module", "-e", check], {encoding: "utf8", windowsHide: true, env: {...process.env, TZ: "America/Los_Angeles"}});
  assert.deepEqual(JSON.parse(result), [3, false, true]);
});

const linter = new Linter();
function verify(source) {
  return linter.verify(source, [{files: ["**/*.jsx"], languageOptions: {ecmaVersion: "latest", sourceType: "module", parserOptions: {ecmaFeatures: {jsx: true}}},
    plugins: {agenteam: {rules: {controls: platformControlsRule}}}, rules: {"agenteam/controls": "error"}}], {filename: "example.jsx"});
}

test("业务页面不能直接添加原生控件或绕过公共日期选择器", () => {
  for (const element of ["input", "select", "textarea", "button", "datalist"]) {
    assert.equal(verify(`const control = <${element}/>;`)[0].messageId, "native");
  }
  assert.equal(verify('import {Input} from "@/components/ui/shadcn/input"; const control = <Input type="date"/>;')[0].messageId, "picker");
  assert.equal(verify('import {Input} from "@/components/ui/input"; const control = <Input type="color"/>;')[0].messageId, "unsupported");
});

test("公共日期控件、组件别名和隐藏上传输入继续允许使用", () => {
  for (const type of ["date", "time", "datetime-local", "number", "search"]) {
    assert.deepEqual(verify(`import {Input as FieldInput} from "@/components/ui/input"; const control = <FieldInput type="${type}"/>;`), []);
  }
  for (const hidden of ["hidden", "hidden={true}"]) {
    assert.deepEqual(verify(`import {Input} from "@/components/ui/input"; const control = <Input type="file" ${hidden}/>;`), []);
  }
  assert.equal(verify('import {Input} from "@/components/ui/input"; const control = <Input type="file" hidden={false}/>;')[0].messageId, "file");
  assert.equal(verify('import {Input} from "@/components/ui/input"; const control = <Input type="file"/>;')[0].messageId, "file");
});
