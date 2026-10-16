import type {WorkflowGraph} from "../types/workflow";

/** 配对来自连线：每条分支都到达同一合并，且没有绕过并行入口的路径。 */
export function synchronizeParallelPairs(graph: WorkflowGraph): WorkflowGraph {
  const next = new Map(graph.nodes.map((node) => [node.nodeId, graph.edges.filter((edge) => edge.source === node.nodeId).map((edge) => edge.target)]));
  const previous = new Map(graph.nodes.map((node) => [node.nodeId, graph.edges.filter((edge) => edge.target === node.nodeId).map((edge) => edge.source)]));
  const allReach = (id: string, target: string, links: Map<string, string[]>, seen = new Set<string>(), memo = new Map<string, boolean>()): boolean => {
    if (id === target) {
      return true;
    }
    if (seen.has(id)) {
      return false;
    }
    if (memo.has(id)) {
      return memo.get(id)!;
    }
    const targets = links.get(id) ?? [];
    const matches = targets.length > 0 && targets.every((child) => allReach(child, target, links, new Set([...seen, id]), memo));
    memo.set(id, matches);
    return matches;
  };
  const branchesDistinct = (branches: string[], join: string) => {
    const seen = new Set<string>();
    for (const branch of branches) {
      const members = new Set<string>(), queue = [branch];
      while (queue.length) {
        const id = queue.pop()!;
        if (id === join || members.has(id)) {
          continue;
        }
        if (seen.has(id)) {
          return false;
        }
        members.add(id);
        queue.push(...(next.get(id) ?? []));
      }
      members.forEach((id) => seen.add(id));
    }
    return true;
  };
  const pairs = new Map<string, string>(), joins = new Map<string, string>();
  for (const node of graph.nodes.filter((item) => item.type === "parallel")) {
    const branches = next.get(node.nodeId) ?? [];
    if (branches.length < 2 || branches.length > 4) {
      continue;
    }
    const candidates = graph.nodes.filter((item) => item.type === "join" && !joins.has(item.nodeId)
      && branches.every((branch) => allReach(branch, item.nodeId, next))
      && allReach(item.nodeId, node.nodeId, previous) && branchesDistinct(branches, item.nodeId));
    if (candidates.length === 1) {
      pairs.set(node.nodeId, candidates[0].nodeId);
      joins.set(candidates[0].nodeId, node.nodeId);
    }
  }
  return {...graph, nodes: graph.nodes.map((node) => {
    const key = node.type === "parallel" ? "joinNodeId" : node.type === "join" ? "parallelNodeId" : null;
    const paired = (node.type === "parallel" ? pairs : joins).get(node.nodeId) ?? null;
    return key && node.config[key] !== paired ? {...node, config: {...node.config, [key]: paired}} : node;
  })};
}
