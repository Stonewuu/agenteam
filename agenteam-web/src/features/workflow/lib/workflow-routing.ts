import type {WorkflowGraph, WorkflowNode} from "../types/workflow";

type Point = {x: number; y: number};
type Box = {left: number; right: number; top: number; bottom: number};
type Curve = [Point, Point, Point, Point];
export type WorkflowRoute = {path: string; points: Point[]; labelX: number; labelY: number};
export type WorkflowRoutingGraph = {nodes: Pick<WorkflowNode, "nodeId" | "type" | "position">[]; edges: WorkflowGraph["edges"]};
const width = 205, height = 112, clearance = 24;

function compact(points: Point[]): Point[] {
  const result: Point[] = [];
  for (const point of points) {
    const last = result.at(-1), before = result.at(-2);
    if (last?.x === point.x && last.y === point.y) {
      continue;
    }
    if (before && last && ((before.x === last.x && last.x === point.x) || (before.y === last.y && last.y === point.y))) {
      result.pop();
    }
    result.push(point);
  }
  return result;
}

function intersects(a: Point, b: Point, box: Box): boolean {
  return a.x === b.x ? a.x > box.left && a.x < box.right && Math.max(a.y, b.y) > box.top && Math.min(a.y, b.y) < box.bottom
    : a.y > box.top && a.y < box.bottom && Math.max(a.x, b.x) > box.left && Math.min(a.x, b.x) < box.right;
}

function cross(a: Point, b: Point, c: Point, d: Point): boolean {
  if (a.x === b.x && c.y === d.y) {
    return a.x > Math.min(c.x, d.x) && a.x < Math.max(c.x, d.x) && c.y > Math.min(a.y, b.y) && c.y < Math.max(a.y, b.y);
  }
  return a.y === b.y && c.x === d.x ? cross(c, d, a, b) : false;
}

function midpoint(a: Point, b: Point): Point {
  return {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2};
}

/** 逐次拆分曲线的控制范围，避免只检查折点而漏掉弯曲部分穿过节点的情况。 */
function curveIntersects(curve: Curve, box: Box, depth = 0): boolean {
  if (Math.max(...curve.map((point) => point.x)) <= box.left || Math.min(...curve.map((point) => point.x)) >= box.right ||
    Math.max(...curve.map((point) => point.y)) <= box.top || Math.min(...curve.map((point) => point.y)) >= box.bottom) {
    return false;
  }
  if (depth === 8) {
    return true;
  }
  const [a, b, c, d] = curve, ab = midpoint(a, b), bc = midpoint(b, c), cd = midpoint(c, d);
  const left = midpoint(ab, bc), right = midpoint(bc, cd), center = midpoint(left, right);
  return curveIntersects([a, ab, left, center], box, depth + 1) || curveIntersects([center, right, cd, d], box, depth + 1);
}

function curvePath([, first, second, end]: Curve): string {
  return ` C ${first.x} ${first.y} ${second.x} ${second.y} ${end.x} ${end.y}`;
}

function route(points: Point[], boxes: Box[]): WorkflowRoute {
  let path = `M ${points[0].x} ${points[0].y}`, longest = 0, labelX = points[0].x, labelY = points[0].y;
  const start = points[0], end = points.at(-1)!, middleX = (start.x + end.x) / 2;
  const direct: Curve = [start, {x: middleX, y: start.y}, {x: middleX, y: end.y}, end];
  if (end.x > start.x && !boxes.some((box) => curveIntersects(direct, box))) {
    return {path: path + curvePath(direct), points, labelX: middleX, labelY: (start.y + end.y) / 2};
  }
  for (let index = 1; index < points.length; index++) {
    const previous = points[index - 1], point = points[index], next = points[index + 1];
    const length = Math.abs(point.x - previous.x) + Math.abs(point.y - previous.y);
    if (length > longest) {
      longest = length;
      labelX = (previous.x + point.x) / 2;
      labelY = (previous.y + point.y) / 2;
    }
    if (next) {
      let radius = Math.min(64, length / 2, (Math.abs(next.x - point.x) + Math.abs(next.y - point.y)) / 2);
      const corner = (distance: number): Curve => {
        const before = {x: point.x - Math.sign(point.x - previous.x) * distance, y: point.y - Math.sign(point.y - previous.y) * distance};
        const after = {x: point.x + Math.sign(next.x - point.x) * distance, y: point.y + Math.sign(next.y - point.y) * distance};
        return [before, midpoint(before, point), midpoint(point, after), after];
      };
      let curve = corner(radius);
      while (radius > 1 && boxes.some((box) => curveIntersects(curve, box))) {
        radius /= 2;
        curve = corner(radius);
      }
      path += ` L ${curve[0].x} ${curve[0].y}` + curvePath(curve);
    } else {
      path += ` L ${point.x} ${point.y}`;
    }
  }
  return {path, points, labelX, labelY};
}

/** 用平滑曲线连接节点；需要绕行时保留避让路径，并尽量减少交叉。 */
export function routeWorkflowEdges(graph: WorkflowRoutingGraph): Map<string, WorkflowRoute> {
  const nodes = new Map(graph.nodes.map((node) => [node.nodeId, node]));
  const boxes = graph.nodes.map((node) => ({id: node.nodeId, left: node.position.x - 10, right: node.position.x + width + 10,
    top: node.position.y - 10, bottom: node.position.y + height + 10}));
  const result = new Map<string, WorkflowRoute>();
  for (const edge of graph.edges) {
    const source = nodes.get(edge.source), target = nodes.get(edge.target);
    if (!source || !target) {
      continue;
    }
    const branches = source.type === "condition" ? ["true", "false"] : source.type === "approval" ? ["approve", "reject"] : ["default"];
    const start = {x: source.position.x + width, y: source.position.y + height * (branches.indexOf(edge.branch) + 1) / (branches.length + 1)};
    const end = {x: target.position.x, y: target.position.y + height / 2};
    const candidates: Point[][] = [];
    if (end.x > start.x) {
      const middle = (start.x + end.x) / 2;
      candidates.push(compact([start, {x: middle, y: start.y}, {x: middle, y: end.y}, end]));
    }
    const lanes = new Set([start.y, end.y, (start.y + end.y) / 2, ...boxes.flatMap((box) => [box.top - clearance, box.bottom + clearance])]);
    for (const y of lanes) {
      candidates.push(compact([start, {x: start.x + clearance, y: start.y}, {x: start.x + clearance, y},
        {x: end.x - clearance, y}, {x: end.x - clearance, y: end.y}, end]));
    }
    let best: Point[] | undefined, bestCost = Infinity;
    for (const points of candidates) {
      if (points.length < 2) {
        continue;
      }
      let cost = (points.length - 2) * 16;
      if (points[1].x <= start.x || points.at(-2)!.x >= end.x) {
        cost += 1_000_000;
      }
      for (let index = 1; index < points.length; index++) {
        const a = points[index - 1], b = points[index];
        cost += Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
        if (boxes.some((box) => !(index === 1 && box.id === source.nodeId) &&
          !(index === points.length - 1 && box.id === target.nodeId) && intersects(a, b, box))) {
          cost += 1_000_000;
        }
        for (const previous of graph.edges) {
          if (previous.source === edge.source || previous.target === edge.target) {
            continue;
          }
          const placed = result.get(previous.edgeId);
          if (placed) {
            for (let part = 1; part < placed.points.length; part++) {
              if (cross(a, b, placed.points[part - 1], placed.points[part])) {
                cost += 120;
              }
            }
          }
        }
      }
      if (cost < bestCost) {
        best = points;
        bestCost = cost;
      }
    }
    if (best) {
      result.set(edge.edgeId, route(best, boxes.map((box) => ({left: box.left + 10, right: box.right - 10,
        top: box.top + 10, bottom: box.bottom - 10}))));
    }
  }
  return result;
}
