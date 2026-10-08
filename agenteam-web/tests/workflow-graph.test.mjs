import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

const source = await readFile(new URL("../src/features/workflow/lib/workflow-graph.ts", import.meta.url), "utf8");
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022 } }).outputText;
const { initialGraph, insertNode, connectionError, removeNode, variableOptions } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);

test("修改连接时拒绝循环、自连、重复分支和开始节点入口", () => {
  const { graph } = insertNode(initialGraph(), "transform", "start");
  assert.match(connectionError(graph, { edgeId: "loop", source: "end", target: "transform_1", branch: "default" }), /不能使用/);
  assert.match(connectionError(graph, { edgeId: "self", source: "transform_1", target: "transform_1", branch: "default" }), /自身/);
  assert.match(connectionError(graph, { edgeId: "start", source: "transform_1", target: "start", branch: "default" }), /开始/);
  assert.match(connectionError(graph, { edgeId: "duplicate", source: "transform_1", target: "end", branch: "default" }), /已经存在/);
  const second = insertNode(graph, "transform", "transform_1").graph;
  const outgoing = second.edges.find((edge) => edge.source === "transform_2");
  assert.match(connectionError(second, { ...outgoing, target: "transform_1" }, outgoing.edgeId), /循环/);
});

test("条件后插入节点只修改选中的分支，另一路保留原连接", () => {
  const { graph } = insertNode(initialGraph(), "condition", "start");
  const unchanged = graph.edges.find((edge) => edge.source === "condition_1" && edge.branch === "false");
  const next = insertNode(graph, "transform", "condition_1").graph;
  assert.deepEqual(next.edges.find((edge) => edge.edgeId === unchanged.edgeId), unchanged);
  assert.equal(next.edges.find((edge) => edge.source === "condition_1" && edge.branch === "true").target, "transform_1");
  assert.equal(next.edges.find((edge) => edge.source === "transform_1").target, "end");
});

test("添加并行同时创建两条独立分支和匹配汇合，删除只去除相邻连接", () => {
  const { graph, selected } = insertNode(initialGraph(), "parallel", "start");
  const parallel = graph.nodes.find((node) => node.nodeId === selected), join = graph.nodes.find((node) => node.nodeId === parallel.config.joinNodeId);
  assert.equal(join.config.parallelNodeId, selected);
  const branches = graph.edges.filter((edge) => edge.source === selected);
  assert.equal(branches.length, 2); assert.notEqual(branches[0].target, branches[1].target);
  assert.equal(new Set(graph.edges.map((edge) => edge.edgeId)).size, graph.edges.length);
  for (const edge of graph.edges) {
    assert.match(edge.edgeId, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  }
  for (const branch of branches) {
    assert.equal(graph.edges.find((edge) => edge.source === branch.target).target, join.nodeId);
  }
  const removed = removeNode(graph, branches[0].target);
  assert.equal(removed.nodes.length, graph.nodes.length - 1);
  assert.equal(removed.edges.length, graph.edges.length - 2);
  assert.ok(removed.edges.some((edge) => edge.source === branches[1].target));
});

test("变量选项只包含上游输出，并保留图形整理前后的引用编号", () => {
  const { graph } = insertNode(initialGraph(), "parallel", "start");
  const first = "parallel_1_first", second = "parallel_1_second";
  assert.ok(!variableOptions(graph, first).some((option) => option.path.startsWith(`steps.${second}.`)));
  assert.ok(!variableOptions(graph, first).some((option) => option.path.startsWith(`steps.${first}.`)));
  const end = variableOptions(graph, "end");
  assert.ok(end.some((option) => option.path === `steps.${first}.output`));
  assert.ok(end.some((option) => option.path === `steps.${second}.output`));
  const afterRemoval = removeNode(graph, "parallel_1");
  const replacement = insertNode(afterRemoval, "parallel", "start");
  assert.equal(new Set(replacement.graph.nodes.map((node) => node.nodeId)).size, replacement.graph.nodes.length);
});

test("新增节点保留已有拖动位置，并行节点与两路配置各有独立位置", () => {
  const graph = initialGraph();
  graph.nodes[0].position = { x: 410, y: 280 };
  const next = insertNode(graph, "parallel", "start").graph;
  for (const node of graph.nodes) {
assert.deepEqual(next.nodes.find((item) => item.nodeId === node.nodeId).position, node.position);
}
  const added = next.nodes.filter((node) => !graph.nodes.some((original) => original.nodeId === node.nodeId));
  assert.equal(new Set(added.map((node) => `${node.position.x}:${node.position.y}`)).size, 4);
});
