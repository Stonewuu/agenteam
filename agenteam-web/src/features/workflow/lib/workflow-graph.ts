import type {Branch, NodeType, WorkflowEdge, WorkflowGraph, WorkflowNode} from "../types/workflow";

export const nodeNames: Record<NodeType, string> = {
  start: "开始",
  agent: "智能体",
  skill: "技能",
  tool: "工具",
  condition: "条件",
  transform: "数据整理",
  approval: "人工确认",
  parallel: "并行",
  join: "汇合",
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
    parallel: {joinNodeId: "join"},
    join: {parallelNodeId: "parallel"},
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

export function removeNode(graph: WorkflowGraph, id: string): WorkflowGraph {
  return {
    nodes: graph.nodes.filter((node) => node.nodeId !== id),
    edges: graph.edges.filter((item) => item.source !== id && item.target !== id)
  };
}

export function arrangeGraph(graph: WorkflowGraph): WorkflowGraph {
  const levels = new Map<string, number>(), pending = [...graph.nodes];
  for (let round = 0; pending.length && round < graph.nodes.length; round++) {
    for (let index = pending.length - 1; index >= 0; index--) {
      const node = pending[index],
        parents = graph.edges.filter((item) => item.target === node.nodeId).map((item) => item.source);
      if (parents.every((id) => levels.has(id))) {
        levels.set(node.nodeId, parents.length ? Math.max(...parents.map((id) => levels.get(id)!)) + 1 : 0);
        pending.splice(index, 1);
      }
    }
  }
  const columns = new Map<number, number>();
  return {
    ...graph, nodes: graph.nodes.map((node) => {
      const level = levels.get(node.nodeId) ?? 0, column = columns.get(level) ?? 0;
      columns.set(level, column + 1);
      return {...node, position: {x: 40 + level * 300, y: 40 + column * 170}};
    })
  };
}

/** 新节点接在选中的一条连接中；并行同时创建两路和相应汇合。 */
export function insertNode(graph: WorkflowGraph, type: Exclude<NodeType, "start" | "end" | "join">, selected: string): {
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
  const node = createNode(type, id(type), {
    x: sourcePosition.x + 300,
    y: Math.max(0, ...graph.nodes.map((item) => item.position.y)) + 170
  });
  const previous = graph.edges.find((item) => item.source === selected) ?? graph.edges.find((item) => graph.nodes.find((node) => node.nodeId === item.target)?.type === "end");
  const nodes = [...graph.nodes, node], edges = graph.edges.filter((item) => item !== previous);
  if (previous) {
    edges.push(edge(previous.source, node.nodeId, previous.branch));
  }
  const target = previous?.target ?? graph.nodes.find((item) => item.type === "end")!.nodeId;
  if (type === "parallel") {
    const join = createNode("join", id("join"), {x: node.position.x + 600, y: node.position.y});
    const first = createNode("transform", `${node.nodeId}_first`, {x: node.position.x + 300, y: node.position.y}),
      second = createNode("transform", `${node.nodeId}_second`, {x: node.position.x + 300, y: node.position.y + 170});
    node.config.joinNodeId = join.nodeId;
    join.config.parallelNodeId = node.nodeId;
    first.name = "分支一";
    second.name = "分支二";
    nodes.push(first, second, join);
    edges.push(edge(node.nodeId, first.nodeId), edge(node.nodeId, second.nodeId), edge(first.nodeId, join.nodeId), edge(second.nodeId, join.nodeId), edge(join.nodeId, target));
  } else {
    for (const branch of branches(type)) {
      edges.push(edge(node.nodeId, target, branch));
    }
  }
  return {graph: {nodes, edges}, selected: node.nodeId};
}

export function variableOptions(graph: WorkflowGraph, current: string, text: (message: string) => string = (message) => message): {
  path: string;
  label: string
}[] {
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
  const fields = record(record(graph.nodes.find((node) => node.type === "start")?.config.inputSchema).properties);
  const input = [{
    path: "input",
    label: text("全部输入")
  }, ...Object.entries(fields).map(([key, value]) => ({
    path: `input.${key}`,
    label: `${text("输入")} · ${String(record(value).title || key)}`
  }))];
  return [...input, ...graph.nodes.filter((node) => ancestors.has(node.nodeId) && node.type !== "start").flatMap((node) => {
    const base = `steps.${node.nodeId}.output`, results = [{path: base, label: `${node.name} · ${text("全部输出")}`}];
    const fields = node.type === "agent" || node.type === "skill" ? ["text", "result", "citations", "attachments"] : node.type === "transform" ? (node.config.fields as {
      target: string
    }[]).map((field) => field.target) : [];
    const labels: Record<string, string> = {text: "文字", result: "结果", citations: "引用", attachments: "附件"};
    return [...results, ...fields.map((field) => ({
      path: `${base}.${field}`,
      label: `${node.name} · ${labels[field] ? text(labels[field]) : field}`
    }))];
  })];
}
