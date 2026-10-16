import type {WorkflowGraph} from "../types/workflow";

export function graphAtPath(graph: WorkflowGraph, path: string[]): WorkflowGraph {
  return path.reduce((current, id) => current.nodes.find((node) => node.nodeId === id)?.config.body as WorkflowGraph ?? current, graph);
}

export function validGraphPath(graph: WorkflowGraph, path: string[]): string[] {
  let current = graph;
  const valid: string[] = [];
  for (const id of path) {
    const node = current.nodes.find((node) => node.nodeId === id && node.type === "loop");
    if (!node?.config.body) {
      break;
    }
    valid.push(id);
    current = node.config.body as WorkflowGraph;
  }
  return valid;
}

export function replaceGraph(graph: WorkflowGraph, path: string[], next: WorkflowGraph): WorkflowGraph {
  if (!path.length) {
    return {...graph, ...next};
  }
  return {...graph, nodes: graph.nodes.map((node) => node.nodeId === path[0]
    ? {...node, config: {...node.config, body: replaceGraph(node.config.body as WorkflowGraph, path.slice(1), next)}} : node)};
}
