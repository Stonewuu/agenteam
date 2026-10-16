import type {WorkflowGraph, WorkflowNode} from "../types/workflow";

export type Point = WorkflowNode["position"];
export type GroupBounds = {x: number; y: number; width: number; height: number};

/** 文档保存绝对坐标，只有画布适配层使用组内相对坐标。 */
export function workflowGroupBounds(graph: WorkflowGraph): Map<string, GroupBounds> {
  return new Map((graph.groups ?? []).flatMap((group) => {
    const members = graph.nodes.filter((node) => group.nodeIds.includes(node.nodeId));
    if (!members.length) {
      return [];
    }
    const x = Math.min(...members.map((node) => node.position.x)) - 24;
    const y = Math.min(...members.map((node) => node.position.y)) - 56;
    return [[group.groupId, {x, y, width: Math.max(...members.map((node) => node.position.x + 205)) - x + 24,
      height: Math.max(...members.map((node) => node.position.y + 112)) - y + 24}]];
  }));
}

export function absoluteCanvasPositions(nodes: {id: string; parentId?: string; position: Point}[], graph: WorkflowGraph): Record<string, Point> {
  const displayed = new Map(nodes.map((node) => [node.id, node]));
  return Object.fromEntries(graph.nodes.map((node) => {
    const shown = displayed.get(node.nodeId);
    const parent = shown?.parentId ? displayed.get(shown.parentId) : undefined;
    return [node.nodeId, shown ? {x: shown.position.x + (parent?.position.x ?? 0), y: shown.position.y + (parent?.position.y ?? 0)} : node.position];
  }));
}
