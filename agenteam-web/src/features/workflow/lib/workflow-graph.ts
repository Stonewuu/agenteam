import type {Branch, DependencyOptions, NodeType, WorkflowEdge, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {parameterSchema, selectedWorkflowTool, type ParameterSchema, type VariableOption} from "./workflow-parameters";

export const nodeNames: Record<NodeType, string> = {
  start: "开始",
  agent: "智能体",
  skill: "技能",
  tool: "工具",
  condition: "条件",
  transform: "数据整理",
  approval: "人工确认",
  parallel: "并行",
  join: "合并",
  loop: "循环",
  end: "结束"
};
export const branchNames: Record<Branch, string> = {
  default: "继续",
  true: "成立",
  false: "不成立",
  approve: "同意",
  reject: "拒绝"
};

export function branches(type: NodeType): Branch[] {
  return type === "condition" ? ["true", "false"] : type === "approval" ? ["approve", "reject"] : type === "end" ? [] : ["default"];
}

export function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

export function createNode(type: NodeType, id: string, position: WorkflowNode["position"]): WorkflowNode {
  const configs: Record<NodeType, Record<string, unknown>> = {
    start: {inputSchema: {type: "object", required: ["text"], properties: {text: {type: "string", title: "任务内容"}}}},
    agent: {agentVersionId: null, inputMapping: {text: "${input.text}"}},
    skill: {agentVersionId: null, skillVersionId: null, inputMapping: {text: "${input.text}"}},
    tool: {pluginVersionId: null, toolId: null, inputMapping: {}},
    condition: {condition: {field: "input.text", operator: "exists"}},
    transform: {fields: []},
    approval: {title: "请确认是否继续", description: "", inputMapping: {text: "${input.text}"}},
    parallel: {joinNodeId: null},
    join: {parallelNodeId: null},
    loop: {mode: "count", count: 3, maxIterations: 20, items: "${input.items}", body: loopBody()},
    end: {outputMapping: {text: "${input.text}"}},
  };
  return {
    nodeId: id,
    type,
    name: nodeNames[type],
    position,
    timeoutSeconds: 120,
    failurePolicy: "stop",
    config: configs[type]
  };
}

export function loopBody(): WorkflowGraph & {icon: string; color: string} {
  // 内层起止节点直接构造，避免创建普通节点时递归生成循环。
  const common = {timeoutSeconds: 120, failurePolicy: "stop" as const};
  return {icon: "GitBranch", color: "blue", nodes: [
    {...common, nodeId: "start", type: "start", name: "本次输入", position: {x: 40, y: 80}, config: {
      inputSchema: {type: "object", properties: {
        item: {title: "当前条目"}, index: {type: "integer", title: "条目序号（从 0 开始）"},
        number: {type: "integer", title: "执行次数"}, parent: {type: "object", title: "循环前的输入"}, previous: {title: "上一次结果"}
      }}
    }},
    {...common, nodeId: "end", type: "end", name: "本次结果", position: {x: 350, y: 80}, config: {outputMapping: {result: "${input.item}"}}}
  ], edges: [{edgeId: "start_end", source: "start", target: "end", branch: "default"}]};
}

export function graphNodeCount(graph: WorkflowGraph): number {
  return graph.nodes.reduce((count, node) => count + 1 + (node.type === "loop" ? graphNodeCount(node.config.body as WorkflowGraph) : 0), 0);
}

export function initialGraph(): WorkflowGraph {
  return {
    nodes: [createNode("start", "start", {x: 40, y: 80}), createNode("end", "end", {x: 350, y: 80})],
    edges: [{edgeId: "start_end", source: "start", target: "end", branch: "default"}]
  };
}

export function edge(source: string, target: string, branch: Branch = "default"): WorkflowEdge {
  return {edgeId: crypto.randomUUID(), source, target, branch};
}

export function connectionError(graph: WorkflowGraph, candidate: WorkflowEdge, replacing?: string): string {
  const source = graph.nodes.find((node) => node.nodeId === candidate.source),
    target = graph.nodes.find((node) => node.nodeId === candidate.target);
  if (!source || !target) {
    return "请选择连接的两个节点。";
  }
  if (source.nodeId === target.nodeId) {
    return "节点不能连接自身。";
  }
  if (target.type === "start") {
    return "开始节点不能有前置节点。";
  }
  if (!branches(source.type).includes(candidate.branch)) {
    return "当前节点不能使用这类后续分支。";
  }
  const existing = graph.edges.filter((item) => item.edgeId !== replacing);
  const outgoing = existing.filter((item) => item.source === candidate.source);
  if (outgoing.some((item) => item.target === candidate.target && item.branch === candidate.branch)) {
    return "这条连接已经存在。";
  }
  if (source.type === "parallel" ? outgoing.length >= 4 : outgoing.some((item) => item.branch === candidate.branch)) {
    return source.type === "parallel" ? "并行节点最多连接四条分支。" : "此分支已经连接了后续节点，请修改原连接。";
  }
  const seen = new Set<string>(), pending = [candidate.target];
  while (pending.length) {
    const current = pending.pop()!;
    if (current === candidate.source) {
      return "这条连接会形成循环，请选择其他节点。";
    }
    if (seen.has(current)) {
      continue;
    }
    seen.add(current);
    pending.push(...existing.filter((item) => item.source === current).map((item) => item.target));
  }
  return "";
}

/** 新增始终独立，包含并行与合并；配对只依据用户连线。 */
export function insertNode(graph: WorkflowGraph, type: Exclude<NodeType, "start" | "end">, selected: string,
  options: {position?: WorkflowNode["position"]} = {}): {
  graph: WorkflowGraph;
  selected: string
} {
  const id = (kind: NodeType) => {
    let index = 1;
    while (graph.nodes.some((node) => node.nodeId === `${kind}_${index}` || node.nodeId.startsWith(`${kind}_${index}_`))) {
      index++;
    }
    return `${kind}_${index}`;
  };
  const sourcePosition = graph.nodes.find((item) => item.nodeId === selected)?.position ?? {x: 40, y: 40};
  const node = createNode(type, id(type), options.position ?? {
    x: sourcePosition.x + 300,
    y: Math.max(0, ...graph.nodes.map((item) => item.position.y)) + 170
  });
  const inputFields = record(record(graph.nodes.find((item) => item.type === "start")?.config.inputSchema).properties);
  if (Object.hasOwn(inputFields, "item") && !Object.hasOwn(inputFields, "text")) {
    if (Object.hasOwn(node.config, "inputMapping") && type !== "tool") {
      node.config.inputMapping = {text: "${input.item}"};
    }
    if (type === "condition") {
      node.config.condition = {field: "input.item", operator: "exists"};
    }
  }
  const nodes = [...graph.nodes, node], edges = [...graph.edges];
  return {graph: {...graph, nodes, edges}, selected: node.nodeId};
}

export function upstreamNodeIds(graph: WorkflowGraph, current: string) {
  const ancestors = new Set<string>(), pending = [current];
  while (pending.length) {
    const target = pending.pop();
    for (const item of graph.edges.filter((item) => item.target === target)) {
      if (!ancestors.has(item.source) && item.source !== current) {
        ancestors.add(item.source);
        pending.push(item.source);
      }
    }
  }
  return ancestors;
}

export function variableOptions(graph: WorkflowGraph, current: string, text: (message: string) => string = (message) => message,
  dependencies: Record<string, DependencyOptions> = {}): VariableOption[] {
  const ancestors = upstreamNodeIds(graph, current);
  const inputSchema = record(graph.nodes.find((node) => node.type === "start")?.config.inputSchema);
  const fields = record(inputSchema.properties);
  const expand = (schema: ParameterSchema, path: string, label: string, group: string, root = schema, depth = 0): VariableOption[] => {
    const resolved = parameterSchema(schema, root);
    return [{path, label, group, schema: resolved}, ...(depth < 4 ? Object.entries(record(resolved.properties)).flatMap(([name, field]) =>
      /^[^\s.{}]+$/.test(name) ? expand(record(field), `${path}.${name}`, `${label} · ${String(record(field).title || name)}`, group, root, depth + 1) : []) : [])];
  };
  const input = [{
    path: "input",
    label: text("全部输入"), group: text("工作流输入"), schema: inputSchema
  }, ...Object.entries(fields).flatMap(([key, value]) => /^[^\s.{}]+$/.test(key) ? expand(record(value), `input.${key}`, String(record(value).title || key), text("工作流输入"), inputSchema) : [])];
  return [...input, ...graph.nodes.filter((node) => ancestors.has(node.nodeId) && node.type !== "start").flatMap((node) => {
    const base = `steps.${node.nodeId}.output`, results: VariableOption[] = [{path: base, label: `${node.name} · ${text("全部输出")}`, group: node.name}];
    if (node.type === "tool") {
      const options = dependencies[String(node.config.pluginVersionId)], tool = selectedWorkflowTool(options?.tools ?? [], node.config);
      const root = options?.toolOutputRoots?.[String(tool?.entryId ?? tool?.id)];
      if (tool?.outputSchema && root !== undefined) {
        return [...results, ...expand(tool.outputSchema, root ? `${base}.${root}` : base, `${node.name} · ${text("结果")}`, node.name).filter((item) => item.path !== base)];
      }
    }
    const fields = node.type === "condition" ? ["matched"] : node.type === "approval" ? ["decision"] : node.type === "loop" ? ["results", "iterations"] : node.type === "agent" || node.type === "skill" ? ["text", "result", "citations", "attachments"] : node.type === "transform" ? (node.config.fields as {
      target: string
    }[]).map((field) => field.target) : [];
    const labels: Record<string, string> = {text: "文字", result: "结果", citations: "引用", attachments: "附件", results: "每次执行结果", iterations: "已执行次数", matched: "条件是否成立", decision: "确认结果"};
    return [...results, ...fields.map((field) => ({
      path: `${base}.${field}`,
      label: `${node.name} · ${labels[field] ? text(labels[field]) : field}`, group: node.name,
      schema: field === "text" ? {type: "string"} : field === "matched" ? {type: "boolean"} : field === "decision" ? {type: "string", enum: ["approve", "reject"]}
        : ["citations", "attachments", "results"].includes(field) ? {type: "array"} : field === "iterations" ? {type: "integer"} : undefined
    }))];
  })];
}
