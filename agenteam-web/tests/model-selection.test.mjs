import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/features/modelprofile/lib/reasoning-effort.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { selectModel, sameModelSelection } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);

test("切换模型保留其支持的等级，不把旧模型等级带入不支持的模型", () => {
  const previous = { modelProfileId: "first", reasoningEffort: "high" };
  assert.deepEqual(selectModel({ id: "second", reasoningEfforts: ["low", "high"] }, previous), { modelProfileId: "second", reasoningEffort: "high" });
  assert.deepEqual(selectModel({ id: "third", reasoningEfforts: ["low"] }, previous), { modelProfileId: "third", reasoningEffort: null });
  assert.deepEqual(selectModel({ id: "plain", reasoningEfforts: [] }, previous), { modelProfileId: "plain", reasoningEffort: null });
  assert.deepEqual(previous, { modelProfileId: "first", reasoningEffort: "high" });
});

test("模型默认等级和关闭思考是不同选择，比较时保留区别", () => {
  const defaults = { modelProfileId: "first", reasoningEffort: null };
  assert.equal(sameModelSelection(defaults, { modelProfileId: "first" }), true);
  assert.equal(sameModelSelection(defaults, { modelProfileId: "first", reasoningEffort: "none" }), false);
  assert.equal(sameModelSelection(defaults, { modelProfileId: "second", reasoningEffort: null }), false);
  assert.deepEqual(selectModel({ id: "second", reasoningEfforts: ["high"] }, defaults), { modelProfileId: "second", reasoningEffort: null });
});
