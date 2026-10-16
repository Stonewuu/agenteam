import type {ElkNode} from "elkjs/lib/elk-api";
import type {WorkflowGraph} from "../types/workflow";

const options = {
  "elk.algorithm": "layered", "elk.direction": "RIGHT", "elk.edgeRouting": "ORTHOGONAL",
  "elk.hierarchyHandling": "INCLUDE_CHILDREN", "elk.spacing.nodeNode": "64",
  "elk.layered.spacing.nodeNodeBetweenLayers": "100", "elk.padding": "[top=56,left=24,bottom=24,right=24]",
  "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF", "elk.layered.crossingMinimization.strategy": "LAYER_SWEEP",
};

/** 只在用户整理时加载布局库；布局结果仅更新节点位置，不改执行关系。 */
export async function arrangeWorkflow(graph: WorkflowGraph): Promise<WorkflowGraph> {
  const {default: ELK} = await import("elkjs/lib/elk.bundled.js");
  const elk = new ELK();
  const leaf = (id: string): ElkNode => ({id, width: 205, height: 112});
  const grouped = new Set(graph.groups?.flatMap((group) => group.nodeIds));
  const result = await elk.layout({id: "@root", layoutOptions: options, children: [
    ...(graph.groups ?? []).map((group) => ({id: `@${group.groupId}`, layoutOptions: options, children: group.nodeIds.map(leaf)})),
    ...graph.nodes.filter((node) => !grouped.has(node.nodeId)).map((node) => leaf(node.nodeId)),
  ], edges: graph.edges.map((edge) => ({id: edge.edgeId, sources: [edge.source], targets: [edge.target]}))});
  const positions = new Map<string, {x: number; y: number}>();
  const visit = (node: ElkNode, x = 0, y = 0) => {
    const point = {x: x + (node.x ?? 0), y: y + (node.y ?? 0)};
    if (!node.children) {
      positions.set(node.id, point);
    }
    node.children?.forEach((child) => visit(child, point.x, point.y));
  };
  visit(result);
  return {...graph, nodes: graph.nodes.map((node) => ({...node, position: positions.get(node.nodeId) ?? node.position}))};
}
