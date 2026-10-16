import type {Branch, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {connectionError, edge, graphNodeCount, insertNode} from "./workflow-graph";
import {synchronizeParallelPairs} from "./workflow-parallel";

export type ConnectionOrigin = {source: string; branch: Branch};
export type WorkflowCommand =
  | {type: "add"; kind: Parameters<typeof insertNode>[1]; position?: WorkflowNode["position"]; origin?: ConnectionOrigin}
  | {type: "connect"; source: string; target: string; branch: Branch}
  | {type: "reconnect"; id: string; source: string; target: string; branch: Branch}
  | {type: "deleteEdge"; id: string}
  | {type: "move"; positions: Record<string, WorkflowNode["position"]>}
  | {type: "update"; node: WorkflowNode}
  | {type: "delete"; ids: string[]}
  | {type: "copy"; ids: string[]}
  | {type: "group"; ids: string[]; name: string}
  | {type: "ungroup"; id: string}
  | {type: "renameGroup"; id: string; name: string};
export type CommandResult = {graph: WorkflowGraph; selected?: string[]; error?: string};

function pruneGroups(graph: WorkflowGraph, removed: Set<string>) {
  return graph.groups?.map((group) => ({...group, nodeIds: group.nodeIds.filter((id) => !removed.has(id))}))
    .filter((group) => group.nodeIds.length >= 2);
}

/** 所有交互入口共用文档修改；一次命令返回一次可撤销的完整结果。 */
export function executeWorkflowCommand(graph: WorkflowGraph, command: WorkflowCommand): CommandResult {
  const result = applyCommand(graph, command);
  return result.error || !["add", "connect", "reconnect", "deleteEdge", "delete", "copy"].includes(command.type)
    ? result : {...result, graph: synchronizeParallelPairs(result.graph)};
}

function applyCommand(graph: WorkflowGraph, command: WorkflowCommand): CommandResult {
  switch (command.type) {
    case "add": {
      const added = insertNode(graph, command.kind, "", {position: command.position});
      if (command.origin) {
        const candidate = edge(command.origin.source, added.selected, command.origin.branch);
        const error = graph.edges.length >= 100 ? "流程最多包含 100 条连接。" : connectionError(added.graph, candidate);
        if (error) {
          return {graph, error};
        }
        added.graph.edges.push(candidate);
      }
      return {graph: added.graph, selected: [added.selected]};
    }
    case "connect": {
      const candidate = edge(command.source, command.target, command.branch);
      const error = graph.edges.length >= 100 ? "流程最多包含 100 条连接。" : connectionError(graph, candidate);
      return error ? {graph, error} : {graph: {...graph, edges: [...graph.edges, candidate]}};
    }
    case "reconnect": {
      const original = graph.edges.find((edge) => edge.edgeId === command.id);
      if (!original) {
        return {graph};
      }
      const candidate = {...original, source: command.source, target: command.target, branch: command.branch};
      const error = connectionError(graph, candidate, original.edgeId);
      return error ? {graph, error} : {graph: {...graph, edges: graph.edges.map((edge) => edge.edgeId === original.edgeId ? candidate : edge)}};
    }
    case "deleteEdge":
      return {graph: {...graph, edges: graph.edges.filter((edge) => edge.edgeId !== command.id)}};
    case "move":
      return {graph: {...graph, nodes: graph.nodes.map((node) => command.positions[node.nodeId]
        ? {...node, position: command.positions[node.nodeId]} : node)}};
    case "update":
      return {graph: {...graph, nodes: graph.nodes.map((node) => node.nodeId === command.node.nodeId ? command.node : node)}};
    case "delete": {
      const removed = new Set(graph.nodes.filter((node) => command.ids.includes(node.nodeId) && !["start", "end"].includes(node.type)).map((node) => node.nodeId));
      return {graph: {...graph, nodes: graph.nodes.filter((node) => !removed.has(node.nodeId)),
        edges: graph.edges.filter((item) => !removed.has(item.source) && !removed.has(item.target)),
        ...(graph.groups ? {groups: pruneGroups(graph, removed)} : {})}, selected: []};
    }
    case "copy": {
      const originals = graph.nodes.filter((node) => command.ids.includes(node.nodeId) && !["start", "end"].includes(node.type));
      const ids = new Map(originals.map((node) => [node.nodeId, `${node.type}_${crypto.randomUUID().slice(0, 8)}`]));
      const copies = originals.map((node) => {
        const copy = structuredClone(node);
        copy.nodeId = ids.get(node.nodeId)!;
        copy.name = `${node.name.slice(0, 46)} 副本`;
        copy.position = {x: node.position.x + 40, y: node.position.y + 150};
        for (const key of ["joinNodeId", "parallelNodeId"]) {
          if (ids.has(String(copy.config[key]))) {
            copy.config[key] = ids.get(String(copy.config[key]));
          }
        }
        return copy;
      });
      const copiedGroups = graph.groups?.filter((group) => group.nodeIds.every((id) => ids.has(id))).map((group) => ({
        ...group, groupId: `group_${crypto.randomUUID().slice(0, 8)}`, nodeIds: group.nodeIds.map((id) => ids.get(id)!)
      }));
      return {graph: {...graph, nodes: [...graph.nodes, ...copies], edges: [...graph.edges,
        ...graph.edges.filter((item) => ids.has(item.source) && ids.has(item.target)).map((item) => edge(ids.get(item.source)!, ids.get(item.target)!, item.branch))],
        ...(copiedGroups?.length ? {groups: [...graph.groups!, ...copiedGroups]} : {})}, selected: copies.map((node) => node.nodeId)};
    }
    case "group": {
      const ids = [...new Set(command.ids)].filter((id) => graph.nodes.some((node) => node.nodeId === id));
      if (ids.length < 2) {
        return {graph};
      }
      const groupId = `group_${crypto.randomUUID().slice(0, 8)}`;
      return {graph: {...graph, groups: [...(pruneGroups(graph, new Set(ids)) ?? []),
        {groupId, name: command.name.trim().slice(0, 50) || "分组", nodeIds: ids}]}, selected: [groupId]};
    }
    case "ungroup":
      return {graph: {...graph, groups: graph.groups?.filter((group) => group.groupId !== command.id)},
        selected: graph.groups?.find((group) => group.groupId === command.id)?.nodeIds};
    case "renameGroup":
      return {graph: {...graph, groups: graph.groups?.map((group) => group.groupId === command.id
        ? {...group, name: command.name.slice(0, 50)} : group)}};
  }
}

export function expandedSelection(graph: WorkflowGraph, selection: string[]): string[] {
  return [...new Set(selection.flatMap((id) => graph.groups?.find((group) => group.groupId === id)?.nodeIds ?? [id]))];
}

export function commandFitsLimits(rootCount: number, before: WorkflowGraph, next: WorkflowGraph) {
  return rootCount + graphNodeCount(next) - graphNodeCount(before) <= 50 && next.edges.length <= 100;
}
