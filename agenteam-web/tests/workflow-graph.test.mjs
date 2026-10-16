import test from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {createRequire} from "node:module";
import {pathToFileURL} from "node:url";
import ts from "typescript";

const require = createRequire(import.meta.url);
const cache = new Map();
async function sourceUrl(name) {
  if (cache.has(name)) {
    return cache.get(name);
  }
  const source = await readFile(new URL(`../src/features/workflow/lib/${name}.ts`, import.meta.url), "utf8");
  let compiled = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ES2022}}).outputText;
  for (const match of compiled.matchAll(/from "\.\/([^"\n]+)"/g)) {
    compiled = compiled.replace(match[0], `from "${await sourceUrl(match[1])}"`);
  }
  compiled = compiled.replace('import("elkjs/lib/elk.bundled.js")', `import("${pathToFileURL(require.resolve("elkjs/lib/elk.bundled.js"))}")`);
  const url = `data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`;
  cache.set(name, url);
  return url;
}
const {initialGraph, insertNode, connectionError, variableOptions, createNode, graphNodeCount} = await import(await sourceUrl("workflow-graph"));
const {executeWorkflowCommand: execute, expandedSelection} = await import(await sourceUrl("workflow-commands"));
const {workflowGroupBounds, absoluteCanvasPositions} = await import(await sourceUrl("workflow-canvas-model"));
const {arrangeWorkflow} = await import(await sourceUrl("workflow-layout"));
const parameters = await import(await sourceUrl("workflow-parameters"));
const connect = (graph, source, target, branch = "default") => execute(graph, {type: "connect", source, target, branch}).graph;
const line = () => {
  let graph = insertNode({...initialGraph(), edges: []}, "transform", "").graph;
  graph = connect(graph, "start", "transform_1");
  return connect(graph, "transform_1", "end");
};

test("所有普通新增保持原连接；只有明确端口来源才连接，分支占用失败不留下新节点", () => {
  const original = initialGraph();
  for (const kind of ["transform", "condition", "loop", "parallel", "join"]) {
    const added = execute(original, {type: "add", kind, position: {x: -180, y: 320}});
    assert.deepEqual(added.graph.edges.filter((item) => original.edges.some((edge) => edge.edgeId === item.edgeId)), original.edges);
    assert.ok(!added.graph.edges.some((item) => item.source === "start" && item.target !== "end"));
    assert.deepEqual(added.graph.nodes.find((node) => node.nodeId === added.selected[0]).position, {x: -180, y: 320});
  }
  const blocked = execute(original, {type: "add", kind: "transform", origin: {source: "start", branch: "default"}});
  assert.match(blocked.error, /已经连接/);
  assert.equal(blocked.graph, original);
  let graph = insertNode(original, "condition", "").graph;
  const added = execute(graph, {type: "add", kind: "transform", origin: {source: "condition_1", branch: "false"}});
  assert.equal(added.graph.edges.at(-1).branch, "false");
  assert.equal(added.graph.edges.at(-1).target, added.selected[0]);
  assert.deepEqual(graph.edges, original.edges);
});

test("连接校验拒绝循环、自连、重复分支和开始节点入口；变量只引用上游", () => {
  const graph = line();
  assert.match(connectionError(graph, {source: "end", target: "transform_1", branch: "default"}), /不能使用/);
  assert.match(connectionError(graph, {source: "transform_1", target: "transform_1", branch: "default"}), /自身/);
  assert.match(connectionError(graph, {source: "transform_1", target: "start", branch: "default"}), /开始/);
  assert.match(connectionError(graph, {source: "transform_1", target: "end", branch: "default"}), /已经存在/);
  let two = insertNode(graph, "transform", "").graph;
  two = {...two, edges: two.edges.filter((edge) => edge.source !== "transform_1")};
  two = connect(two, "transform_1", "transform_2");
  assert.match(connectionError(two, {source: "transform_2", target: "transform_1", branch: "default"}), /循环/);
  assert.ok(variableOptions(graph, "end").some((option) => option.path === "steps.transform_1.output"));
  assert.ok(!variableOptions(graph, "transform_1").some((option) => option.path.startsWith("steps.end.")));
  const original = graph.edges.find((edge) => edge.source === "start");
  const reconnected = execute(graph, {type: "reconnect", id: original.edgeId, source: "start", target: "end", branch: "default"});
  assert.equal(reconnected.graph.edges.find((edge) => edge.edgeId === original.edgeId).target, "end");
  assert.equal(reconnected.graph.edges.length, graph.edges.length);
  assert.equal(execute(graph, {type: "reconnect", id: original.edgeId, source: "transform_1", target: "start", branch: "default"}).graph, graph);
  assert.equal(execute(graph, {type: "deleteEdge", id: original.edgeId}).graph.edges.length, graph.edges.length - 1);
  assert.equal(graph.edges.find((edge) => edge.edgeId === original.edgeId).target, "transform_1");
});

test("分组、复制和删除保留连接语义，组内移动转换为绝对位置，保护起止节点", () => {
  const graph = line();
  const grouped = execute(graph, {type: "group", ids: ["start", "transform_1"], name: "处理"}).graph;
  const group = grouped.groups[0];
  assert.deepEqual(grouped.edges, graph.edges);
  assert.deepEqual(expandedSelection(grouped, [group.groupId, "start"]), ["start", "transform_1"]);
  const box = workflowGroupBounds(grouped).get(group.groupId);
  const shown = [{id: group.groupId, position: {x: box.x + 50, y: box.y + 90}},
    ...grouped.nodes.map((node) => ({id: node.nodeId, parentId: group.nodeIds.includes(node.nodeId) ? group.groupId : undefined,
      position: group.nodeIds.includes(node.nodeId) ? {x: node.position.x - box.x, y: node.position.y - box.y} : node.position}))];
  const moved = execute(grouped, {type: "move", positions: absoluteCanvasPositions(shown, grouped)}).graph;
  assert.equal(moved.nodes[0].position.x, graph.nodes[0].position.x + 50);
  assert.deepEqual(moved.nodes.find((node) => node.nodeId === "end").position, graph.nodes.find((node) => node.nodeId === "end").position);
  const removed = execute(moved, {type: "delete", ids: ["start", "transform_1", "end"]}).graph;
  assert.deepEqual(removed.nodes.map((node) => node.type), ["start", "end"]);
  assert.equal(removed.groups.length, 0);
  const ungrouped = execute(grouped, {type: "ungroup", id: group.groupId}).graph;
  assert.deepEqual(ungrouped.nodes, grouped.nodes);
  assert.deepEqual(ungrouped.edges, grouped.edges);
});

test("复制完整并行组重新映射内部配对，循环副本独立且计入节点限制", () => {
  let graph = initialGraph();
  for (const kind of ["parallel", "transform", "transform", "join"]) {
    graph = execute(graph, {type: "add", kind}).graph;
  }
  graph = connect(graph, "parallel_1", "transform_1");
  graph = connect(graph, "parallel_1", "transform_2");
  graph = connect(graph, "transform_1", "join_1");
  assert.equal(graph.nodes.find((node) => node.type === "join").config.parallelNodeId, null);
  graph = connect(graph, "transform_2", "join_1");
  assert.equal(graph.nodes.find((node) => node.type === "join").config.parallelNodeId, "parallel_1");
  const incomplete = execute(graph, {type: "deleteEdge", id: graph.edges.at(-1).edgeId}).graph;
  assert.equal(incomplete.nodes.find((node) => node.type === "parallel").config.joinNodeId, null);
  const ids = graph.nodes.filter((node) => !["start", "end"].includes(node.type)).map((node) => node.nodeId);
  const grouped = execute(graph, {type: "group", ids, name: "并行处理"}).graph;
  const copied = execute(grouped, {type: "copy", ids});
  assert.equal(copied.graph.groups.length, 2);
  const newParallel = copied.graph.nodes.find((node) => copied.selected.includes(node.nodeId) && node.type === "parallel");
  const join = copied.graph.nodes.find((node) => node.nodeId === newParallel.config.joinNodeId);
  assert.equal(join.config.parallelNodeId, newParallel.nodeId);
  const loop = insertNode(initialGraph(), "loop", "");
  const next = execute(loop.graph, {type: "copy", ids: [loop.selected]});
  next.graph.nodes.at(-1).config.body.nodes[0].name = "副本输入";
  assert.notEqual(loop.graph.nodes.at(-1).config.body.nodes[0].name, "副本输入");
  assert.equal(graphNodeCount(next.graph), 8);
});

test("参数结构解析本地引用、必填与默认值，切换工具保留已有值和额外字段", () => {
  const schema = {type: "object", properties: {url: {$ref: "#/$defs/url"}, enabled: {type: "boolean", default: false},
    options: {type: "object", properties: {limit: {type: "integer", default: 5}}}}, required: ["url"],
    $defs: {url: {type: "string", title: "网页地址", description: "要读取的网址"}}};
  assert.equal(parameters.parameterSchema(schema.properties.url, schema).title, "网页地址");
  const previous = {url: "${input.link}", enabled: true, legacy: 42};
  const next = parameters.parameterDefaults(schema, previous);
  assert.deepEqual(next, {...previous, options: {limit: 5}});
  next.options.limit = 9;
  assert.equal(schema.properties.options.properties.limit.default, 5);
  assert.equal(parameters.referencePath("${steps.tool.output.structuredContent.text}"), "steps.tool.output.structuredContent.text");
  assert.equal(parameters.referencePath("前缀 ${input.text}"), "");
  assert.equal(parameters.uniqueParameterName({text: true, text2: true}, "input.text"), "text3");
  assert.deepEqual(parameters.parameterChoices({oneOf: [{const: 0, title: "关闭"}, {const: 1, title: "开启"}]}),
    [{value: 0, label: "关闭"}, {value: 1, label: "开启"}]);
  const merged = parameters.parameterSchema({allOf: [{properties: {a: {type: "string"}}, required: ["a"]},
    {properties: {b: {type: "number"}}, required: ["b"]}]});
  assert.deepEqual(merged.required, ["a", "b"]);
});

test("数据引用列出嵌套输入和上游工具的真实输出路径，未定义或下游能力不会被猜测", () => {
  let graph = execute({...initialGraph(), edges: []}, {type: "add", kind: "tool"}).graph;
  graph.nodes[0].config.inputSchema.properties.customer = {type: "object", title: "客户", properties: {name: {type: "string", title: "姓名"}}};
  graph.nodes.at(-1).config = {pluginVersionId: "plugin", toolId: "read", inputMapping: {}};
  graph = connect(connect(graph, "start", "tool_1"), "tool_1", "end");
  const dependencies = {plugin: {tools: [{entryId: "read", name: "read_url", outputSchema: {type: "object", properties: {text: {type: "string"}, links: {type: "array"}}}}], toolOutputRoots: {read: "structuredContent"}}};
  const variables = variableOptions(graph, "end", (value) => value, dependencies);
  assert.ok(variables.some((item) => item.path === "input.customer.name" && item.label.includes("姓名")));
  assert.ok(variables.some((item) => item.path === "steps.tool_1.output.structuredContent.links" && item.schema.type === "array"));
  assert.ok(!variables.some((item) => item.path === "steps.tool_1.output.links"));
  assert.ok(!variableOptions(graph, "tool_1", (value) => value, dependencies).some((item) => item.path.startsWith("steps.tool_1.")));
  assert.ok(!variableOptions(graph, "end").some((item) => item.path.includes("structuredContent")));
});

test("成熟布局引擎对齐单链、减少交叉并处理分组外连线，不改变文档语义", async () => {
  const arranged = await arrangeWorkflow(line());
  assert.equal(new Set(arranged.nodes.map((node) => node.position.y)).size, 1);
  const graph = {nodes: ["a", "b", "d", "c"].map((id) => createNode("transform", id, {x: 900, y: 900})), edges: [
    {edgeId: "ac", source: "a", target: "c", branch: "default"}, {edgeId: "bd", source: "b", target: "d", branch: "default"}]};
  const next = await arrangeWorkflow(graph);
  const positions = Object.fromEntries(next.nodes.map((node) => [node.nodeId, node.position]));
  assert.ok((positions.a.y - positions.b.y) * (positions.c.y - positions.d.y) > 0);
  assert.deepEqual(next.edges, graph.edges);
  const grouped = execute(line(), {type: "group", ids: ["start", "transform_1"], name: "处理"}).graph;
  const result = await arrangeWorkflow(grouped);
  assert.deepEqual(result.groups, grouped.groups);
  assert.deepEqual(result.edges, grouped.edges);
  const box = workflowGroupBounds(result).values().next().value;
  const outside = result.nodes.find((node) => node.nodeId === "end").position;
  assert.ok(outside.x >= box.x + box.width || outside.y >= box.y + box.height);
  assert.deepEqual(await arrangeWorkflow(result), result);
  assert.deepEqual(graph.nodes[0].position, {x: 900, y: 900});
});
