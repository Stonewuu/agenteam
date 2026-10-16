import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import ts from "typescript";

async function load(name) {
  const source = await readFile(new URL(`../src/features/workflow/lib/${name}.ts`, import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022}}).outputText;
  return import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
}
const {graphAtPath, replaceGraph, validGraphPath} = await load("workflow-editor-state");
const {workflowNodeSteps} = await load("workflow-step-status");
const {routeWorkflowEdges} = await load("workflow-routing");

test("修改循环内部只替换目标步骤区域并保留外层分组", () => {
  const body = {nodes: [], edges: []};
  const loop = {nodeId: "loop", type: "loop", name: "遍历", position: {x: 10, y: 20}, config: {body}};
  const graph = {nodes: [loop], edges: [], groups: [{groupId: "outer", name: "外层", nodeIds: ["loop", "another"]}]};
  const next = replaceGraph(graph, ["loop"], {nodes: [{nodeId: "added"}], edges: []});
  assert.equal(graphAtPath(next, ["loop"]).nodes[0].nodeId, "added");
  assert.deepEqual(body.nodes, []);
  assert.deepEqual(next.groups, graph.groups);
  assert.deepEqual(validGraphPath(next, ["loop", "removed"]), ["loop"]);
  assert.deepEqual(validGraphPath({nodes: [], edges: []}, ["loop"]), []);

});

test("循环内部相同编号的完成状态不能覆盖外层仍在等待的节点", () => {
  const details = (invocationId, nodeId) => ({resourceId: "flow", invocationId, nodeId});
  const steps = [
    {kind: "workflow", displayOrder: 1, workflow: details("outer", null)},
    {kind: "workflow_node", displayOrder: 2, status: "waiting_approval", workflow: details("outer", "work")},
    {kind: "workflow", displayOrder: 3, workflow: details("inner", null)},
    {kind: "workflow_node", displayOrder: 4, status: "completed", workflow: details("inner", "work")}
  ];
  assert.deepEqual(workflowNodeSteps([...steps].reverse(), "flow"), [steps[1]]);
});

test("连接使用平滑曲线，弯曲和绕行部分都不穿过节点正文", () => {
  const node = (id, x, y) => ({nodeId: id, type: "transform", position: {x, y}});
  const edge = (source, target) => ({edgeId: `${source}_${target}`, source, target, branch: "default"});
  const adjacent = routeWorkflowEdges({nodes: [node("a", 0, 0), node("b", 285, 0)], edges: [edge("a", "b")]}).get("a_b");
  assert.equal(adjacent.points.length, 2);
  const offset = routeWorkflowEdges({nodes: [node("a", 0, 0), node("b", 285, 176)], edges: [edge("a", "b")]}).get("a_b");
  const segments = offset.path.match(/[MLC][^MLC]+/g);
  assert.equal(segments.length, 2);
  assert.equal(segments[1][0], "C");
  assert.equal(offset.labelX, 245);
  assert.equal(offset.labelY, 144);
  for (const targetY of [0, 200, -176]) {
    const nodes = [node("a", 0, 0), node("block", 285, 0), node("b", 570, targetY)];
    const routed = routeWorkflowEdges({nodes, edges: [edge("a", "b")]}).get("a_b");
    const samples = samplePath(routed.path);
    assert.deepEqual(samples[0], {x: 205, y: 56});
    assert.deepEqual(samples.at(-1), {x: 570, y: targetY + 56});
    for (const point of samples) {
      for (const current of nodes) {
        const {x, y} = current.position;
        assert.equal(point.x > x && point.x < x + 205 && point.y > y && point.y < y + 112, false,
          `曲线穿过节点 ${current.nodeId}：${JSON.stringify(point)}`);
      }
    }
  }
});

function samplePath(path) {
  const samples = [];
  for (const segment of path.match(/[MLC][^MLC]+/g)) {
    const values = segment.slice(1).trim().split(/[\s,]+/).map(Number);
    if (segment[0] === "M") {
      samples.push({x: values[0], y: values[1]});
      continue;
    }
    const start = samples.at(-1);
    for (let step = 1; step <= 100; step++) {
      const t = step / 100, u = 1 - t;
      const at = (axis) => segment[0] === "L" ? u * start[axis === 0 ? "x" : "y"] + t * values[axis]
        : u ** 3 * start[axis === 0 ? "x" : "y"] + 3 * u ** 2 * t * values[axis] + 3 * u * t ** 2 * values[axis + 2] + t ** 3 * values[axis + 4];
      samples.push({x: at(0), y: at(1)});
    }
  }
  return samples;
}
