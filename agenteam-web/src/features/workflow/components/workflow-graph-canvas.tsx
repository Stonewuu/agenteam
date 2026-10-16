"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";
import {useEffect, useId, useMemo, useRef, useState} from "react";
import {
  Background, ConnectionLineType, MarkerType, MiniMap, NodeToolbar, Panel, Position, ReactFlow, SelectionMode,
  type Edge, type NodeDimensionChange, type NodeSelectionChange, type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {Button} from "@/components/ui/button";
import {IconCopy, IconLayoutGrid, IconMap, IconMaximize, IconMinus, IconPan, IconPencil, IconPlus, IconSelect, IconStack, IconTrash, IconX} from "@/components/ui/icons";
import type {BlockStatus} from "@/features/agent/types/execution";
import type {Branch, WorkflowGraph} from "../types/workflow";
import type {ConnectionOrigin} from "../lib/workflow-commands";
import {expandedSelection} from "../lib/workflow-commands";
import {absoluteCanvasPositions, workflowGroupBounds, type Point} from "../lib/workflow-canvas-model";
import {branchNames, nodeNames} from "../lib/workflow-graph";
import {workflowIcons, nodeChoices, type AddableNode} from "./workflow-node-catalog";
import {routeWorkflowEdges, type WorkflowRoutingGraph} from "../lib/workflow-routing";
import {WorkflowConnection} from "./workflow-connection";
import {DiagramGroup, DiagramItem, type DiagramNode, type GroupNode} from "./workflow-diagram-node";
import {ContextMenu, ContextMenuTrigger, ContextMenuContent, ContextMenuItem, ContextMenuGroup, ContextMenuLabel, ContextMenuSeparator} from "@/components/ui/shadcn/context-menu";
import styles from "./workflow.module.css";
import canvasStyles from "./workflow-canvas.module.css";

type CanvasNode = DiagramNode | GroupNode;
const nodeTypes = {agenteam: DiagramItem, workflowGroup: DiagramGroup};
const edgeTypes = {agenteam: WorkflowConnection};
const emptyStatuses: Record<string, BlockStatus> = {};
const emptyIds: string[] = [];
const animationDuration = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 180;

type CanvasProps = {
  graph: WorkflowGraph;
  selected?: string;
  selection?: string[];
  onSelection?: (ids: string[]) => void;
  onSelect: (id: string) => void;
  onMove?: (positions: Record<string, Point>) => void;
  onConnect?: (source: string, target: string, branch: Branch) => void;
  onReconnect?: (id: string, source: string, target: string, branch: Branch) => void;
  onDeleteEdge?: (id: string) => void;
  inspectorOpen?: boolean;
  selectedEdge?: string;
  onSelectEdge?: (id: string) => void;
  nodeStatuses?: Record<string, BlockStatus>;
  invalidNodes?: string[];
  fitKey?: number;
  onAdd?: (type: AddableNode, position: Point, origin?: ConnectionOrigin) => void;
  canAdd?: (type: AddableNode) => boolean;
  onCopy?: (ids: string[]) => void;
  onDelete?: (ids: string[]) => void;
  onGroup?: (ids: string[]) => void;
  onUngroup?: (id: string) => void;
  onArrange?: () => void;
};

export function WorkflowGraphCanvas({graph, selected, selection, onSelection, onSelect, onMove, onConnect, onReconnect, onDeleteEdge, inspectorOpen,
  selectedEdge, onSelectEdge, nodeStatuses = emptyStatuses, invalidNodes = emptyIds, fitKey = 0,
  onAdd, canAdd, onCopy, onDelete, onGroup, onUngroup, onArrange}: CanvasProps) {
  const text = useT();
  const canvas = useRef<HTMLDivElement>(null);
  const [instance, setInstance] = useState<ReactFlowInstance<CanvasNode, Edge> | null>(null);
  const fitted = useRef<{instance: ReactFlowInstance<CanvasNode, Edge>; key: number} | null>(null);
  const flowId = useId();
  const [mode, setMode] = useState<"select" | "pan">("select");
  const [minimap, setMinimap] = useState(true);
  const [dragged, setDragged] = useState<Record<string, Point>>({});
  const [measurements, setMeasurements] = useState<Record<string, {width: number; height: number}>>({});
  const [context, setContext] = useState<{point: Point; node?: string}>({point: {x: 40, y: 40}});
  const [drop, setDrop] = useState<{point: Point; screen: Point; origin: ConnectionOrigin} | null>(null);
  const origin = useRef<ConnectionOrigin | null>(null);
  const reconnecting = useRef(false);
  const dropMenu = useRef<HTMLDivElement>(null);
  const chosen = useMemo(() => selection ?? (selected ? [selected] : emptyIds), [selection, selected]);
  const bounds = useMemo(() => workflowGroupBounds(graph), [graph]);
  const measured = graph.nodes.every((node) => measurements[node.nodeId])
    && (graph.groups ?? []).every((group) => measurements[group.groupId]);
  const nodes = useMemo<CanvasNode[]>(() => [
    ...(graph.groups ?? []).flatMap((group): GroupNode[] => {
      const box = bounds.get(group.groupId);
      return box ? [{id: group.groupId, type: "workflowGroup", position: dragged[group.groupId] ?? {x: box.x, y: box.y},
        width: box.width, height: box.height, style: {width: box.width, height: box.height}, measured: measurements[group.groupId],
        selected: chosen.includes(group.groupId), ariaLabel: group.name, data: {name: group.name}, zIndex: -1}] : [];
    }),
    ...graph.nodes.map((node): DiagramNode => {
      const group = graph.groups?.find((group) => group.nodeIds.includes(node.nodeId));
      const box = group ? bounds.get(group.groupId) : undefined;
      return {id: node.nodeId, type: "agenteam", parentId: group?.groupId,
        position: dragged[node.nodeId] ?? {x: node.position.x - (box?.x ?? 0), y: node.position.y - (box?.y ?? 0)},
        selected: chosen.includes(node.nodeId), width: 205, height: 112, measured: measurements[node.nodeId],
        ariaLabel: `${node.name}，${localizeCatalog(nodeNames, text)[node.type]}`, ariaRole: "button",
        data: {item: node, status: nodeStatuses[node.nodeId], invalid: invalidNodes.includes(node.nodeId),
          connectedBranches: graph.edges.filter((edge) => edge.source === node.nodeId).map((edge) => edge.branch)}};
    }),
  ], [graph, bounds, dragged, measurements, chosen, text, nodeStatuses, invalidNodes]);
  const routingInput = JSON.stringify({nodes: graph.nodes.map(({nodeId, type, position}) => ({nodeId, type, position})), edges: graph.edges});
  const routes = useMemo(() => routeWorkflowEdges(JSON.parse(routingInput) as WorkflowRoutingGraph), [routingInput]);
  const moving = Object.keys(dragged).length > 0;
  const edges: Edge[] = graph.edges.map((edge) => ({
    id: edge.edgeId, source: edge.source, target: edge.target, sourceHandle: edge.branch, type: "agenteam",
    data: {route: moving ? undefined : routes.get(edge.edgeId), onDelete: onDeleteEdge}, selected: edge.edgeId === selectedEdge,
    label: edge.branch !== "default" ? text(branchNames[edge.branch]) : undefined,
    markerEnd: {type: MarkerType.ArrowClosed, color: edge.edgeId === selectedEdge ? "var(--accent)" : "var(--workflow-edge)", width: 14, height: 14},
    interactionWidth: 24, labelStyle: {fill: "var(--secondary)", fontSize: 11, fontWeight: 500},
    labelBgStyle: {fill: "var(--surface)", stroke: "var(--border)", strokeWidth: 1},
    ariaLabel: text("{0}到{1}，{2}", [graph.nodes.find((node) => node.nodeId === edge.source)?.name,
      graph.nodes.find((node) => node.nodeId === edge.target)?.name, text(branchNames[edge.branch])]),
  }));
  useEffect(() => {
    if (!instance || !canvas.current) {
      return;
    }
    let previous = {width: canvas.current.clientWidth, height: canvas.current.clientHeight};
    const observer = new ResizeObserver(([entry]) => {
      const next = {width: entry.contentRect.width, height: entry.contentRect.height};
      if ((previous.width > 600) !== (next.width > 600)) {
        void instance.fitView({padding: .2, maxZoom: 1, duration: animationDuration()});
      } else if (previous.width > 0 && next.width > 0) {
        const viewport = instance.getViewport();
        void instance.setViewport({...viewport, x: viewport.x + (next.width - previous.width) / 2,
          y: viewport.y + (next.height - previous.height) / 2});
      }
      previous = next;
    });
    observer.observe(canvas.current);
    return () => observer.disconnect();
  }, [instance]);
  useEffect(() => {
    if (instance && measured) {
      if (fitted.current?.instance !== instance || fitted.current.key !== fitKey) {
        fitted.current = {instance, key: fitKey};
        void instance.fitView({padding: .2, maxZoom: 1, duration: animationDuration()});
      }
    }
  }, [instance, fitKey, measured]);
  useEffect(() => {
    if (!instance || !canvas.current || !selected || !inspectorOpen || !measured) {
      return;
    }
    const focus = () => {
      const width = canvas.current?.clientWidth ?? 0;
      const item = instance.getInternalNode(selected);
      const usable = width - 384;
      if (!item || usable < 340) {
        return;
      }
      const viewport = instance.getViewport();
      const left = item.internals.positionAbsolute.x * viewport.zoom + viewport.x;
      const right = left + (item.measured.width ?? 205) * viewport.zoom;
      const top = item.internals.positionAbsolute.y * viewport.zoom + viewport.y;
      const bottom = top + (item.measured.height ?? 112) * viewport.zoom;
      if (right - left > usable - 80) {
        return;
      }
      const shift = right > usable - 40 ? usable - 40 - right : left < 40 ? 40 - left : 0;
      const bottomLimit = (canvas.current?.clientHeight ?? 0) - 72;
      const vertical = bottom > bottomLimit ? bottomLimit - bottom : top < 72 ? 72 - top : 0;
      if (shift || vertical) {
        void instance.setViewport({...viewport, x: viewport.x + shift, y: viewport.y + vertical}, {duration: animationDuration()});
      }
    };
    // 等待适应画布的过渡结束，再把选中对象移出侧栏覆盖区域。
    const timer = window.setTimeout(focus, animationDuration() + 20);
    return () => window.clearTimeout(timer);
  }, [instance, selected, inspectorOpen, measured, fitKey]);
  useEffect(() => {
    if (drop) {
      dropMenu.current?.querySelector<HTMLButtonElement>("button")?.focus();
    }
  }, [drop]);
  const selectMany = (ids: string[]) => {
    if (onSelection) {
      onSelection(ids);
    } else {
      onSelect(ids[0] ?? "");
    }
  };
  const finishDrag = () => {
    if (instance) {
      onMove?.(absoluteCanvasPositions(instance.getNodes(), graph));
    }
    setDragged({});
  };
  const members = expandedSelection(graph, chosen);

  const group = chosen.length === 1 ? graph.groups?.find((group) => group.groupId === chosen[0]) : undefined;
  const contextNode = graph.nodes.find((node) => node.nodeId === context.node);
  const fit = () => void instance?.fitView({padding: .2, maxZoom: 1, duration: animationDuration()});
  const selectionActions = <>
    {chosen.length === 1 && <Button type="button" className={canvasStyles.action} aria-label={text(onMove ? "编辑节点" : "查看节点")} title={text(onMove ? "编辑节点" : "查看节点")}
      onClick={() => onSelect(chosen[0])}><IconPencil size={17}/><span>{text(onMove ? "编辑" : "查看")}</span></Button>}
    {onCopy && members.some((id) => !["start", "end"].includes(graph.nodes.find((node) => node.nodeId === id)?.type ?? "start")) && <>
      <Button type="button" className={canvasStyles.action} aria-label={text("复制所选节点")} title={text("复制所选节点")}
        onClick={() => onCopy(members)}><IconCopy size={17}/><span>{text("复制")}</span></Button>
      <Button type="button" className={canvasStyles.action} aria-label={text("删除所选节点")} title={text("删除所选节点")}
        onClick={() => onDelete?.(members)}><IconTrash size={17}/><span>{text("删除")}</span></Button>
    </>}
    {group && onUngroup ? <Button type="button" className={canvasStyles.action} aria-label={text("取消分组")} title={text("取消分组")}
      onClick={() => onUngroup(group.groupId)}><IconStack size={17}/><span>{text("取消分组")}</span></Button>
      : members.length >= 2 && onGroup && <Button type="button" className={canvasStyles.action} aria-label={text("创建分组")} title={text("创建分组")}
        onClick={() => onGroup(members)}><IconStack size={17}/><span>{text("分组")}</span></Button>}
  </>;
  return <ContextMenu onOpenChange={(open, details) => {
    if (open && !onAdd) {
      details.cancel();
    }
  }}><ContextMenuTrigger className={styles.canvasFrame} ref={canvas} aria-label={text("流程画布")} tabIndex={0}
    onKeyDown={(event) => {
      if ((event.target as HTMLElement).closest("input,textarea,select,[contenteditable=true]")) {
        return;
      }
      const key = event.key.toLowerCase();
      if (key === "escape") {
        event.stopPropagation();
        setDrop(null);
        selectMany([]);
        onSelect("");
        onSelectEdge?.("");
      } else if ((event.ctrlKey || event.metaKey) && key === "a") {
        event.preventDefault();
        selectMany(graph.nodes.map((node) => node.nodeId));
      } else if ((event.ctrlKey || event.metaKey) && key === "g") {
        event.preventDefault();
        if (event.shiftKey && group) {
          onUngroup?.(group.groupId);
        } else {
          onGroup?.(members);
        }
      } else if ((event.ctrlKey || event.metaKey) && key === "d") {
        event.preventDefault();
        onCopy?.(members);
      } else if (key === "delete" || key === "backspace") {
        event.preventDefault();
        if (selectedEdge) {
          onDeleteEdge?.(selectedEdge);
        } else {
          onDelete?.(members);
        }
      } else if (!event.ctrlKey && !event.metaKey && !event.altKey && (key === "v" || key === "h")) {
        setMode(key === "v" ? "select" : "pan");
      }
    }} onContextMenuCapture={(event) => {
      const target = event.target as HTMLElement;
      const rect = canvas.current?.getBoundingClientRect();
      const client = event.clientX || event.clientY ? {x: event.clientX, y: event.clientY} : {x: (rect?.left ?? 0) + 100, y: (rect?.top ?? 0) + 100};
      setDrop(null);
      setContext({point: instance?.screenToFlowPosition(client) ?? client, node: target.closest(".react-flow__node")?.getAttribute("data-id") ?? undefined});
    }} onPointerDownCapture={(event) => {
      if (drop && !dropMenu.current?.contains(event.target as HTMLElement)) {
        setDrop(null);
      }
      if (event.pointerType === "touch") {
        setContext({point: instance?.screenToFlowPosition({x: event.clientX, y: event.clientY}) ?? {x: 40, y: 40}});
      }
    }}>
    <ReactFlow<CanvasNode, Edge> id={flowId} nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes} onInit={setInstance}
      connectionLineType={ConnectionLineType.Bezier}
      connectionLineStyle={{stroke: "var(--accent)", strokeWidth: 1.7, strokeDasharray: "6 5", strokeLinecap: "round"}}
      panOnScroll zoomOnScroll={false} zoomActivationKeyCode={["Control", "Meta"]} preventScrolling
      fitView fitViewOptions={{padding: .2, maxZoom: 1}} minZoom={.15} maxZoom={2}
      panOnDrag={mode === "pan" ? [0, 1] : [1]} panActivationKeyCode="Space"
      selectionOnDrag={mode === "select"} selectionMode={SelectionMode.Partial} selectionKeyCode="Shift" multiSelectionKeyCode={["Shift", "Meta", "Control"]}
      onNodeDragStop={finishDrag} onSelectionDragStop={finishDrag}
      proOptions={{hideAttribution: true}}
      onNodeClick={(event, node) => {
        onSelectEdge?.("");
        if (event.shiftKey || event.ctrlKey || event.metaKey) {
          const next = chosen.includes(node.id) ? chosen.filter((id) => id !== node.id) : [...chosen, node.id];
          selectMany(next);
          onSelect(next.length === 1 ? next[0] : "");
        } else {
          selectMany([node.id]);
          onSelect(node.id);
        }
      }} onPaneClick={() => {
        onSelect("");
        onSelectEdge?.("");
      }} onEdgeClick={(_, edge) => {
        selectMany([]);
        onSelect("");
        onSelectEdge?.(edge.id);
      }} onConnect={(connection) => {
        if (connection.source && connection.target) {
          onConnect?.(connection.source, connection.target, (connection.sourceHandle as Branch) || "default");
        }
      }} onConnectStart={(_, params) => {
        origin.current = params.handleType === "source" && params.nodeId
          ? {source: params.nodeId, branch: (params.handleId as Branch) || "default"} : null;
      }} onConnectEnd={(event, state) => {
        const source = origin.current;
        origin.current = null;
        if (!onAdd || !source || reconnecting.current || state.isValid || state.toNode || !instance || !canvas.current) {
          return;
        }
        const pointer = "changedTouches" in event ? event.changedTouches[0] : event;
        const rect = canvas.current.getBoundingClientRect();
        if (!pointer || pointer.clientX < rect.left || pointer.clientX > rect.right || pointer.clientY < rect.top || pointer.clientY > rect.bottom) {
          return;
        }
        const client = {x: pointer.clientX, y: pointer.clientY};
        setDrop({origin: source, point: instance.screenToFlowPosition(client),
          screen: {x: Math.max(12, Math.min(client.x - rect.left, rect.width - 282)), y: Math.max(12, Math.min(client.y - rect.top, rect.height - 432))}});
      }} onReconnectStart={() => {
        reconnecting.current = true;
        origin.current = null;
      }} onReconnectEnd={() => {
        reconnecting.current = false;
      }} onReconnect={(edge, connection) => {
        if (connection.source && connection.target) {
          onReconnect?.(edge.id, connection.source, connection.target, (connection.sourceHandle as Branch) || "default");
        }
      }} onEdgesChange={(changes) => {
        const selected = changes.find((change) => change.type === "select" && change.selected);
        if (selected && "id" in selected) {
          selectMany([]);
          onSelect("");
          onSelectEdge?.(selected.id);
        } else if (changes.some((change) => change.type === "select" && !change.selected && change.id === selectedEdge)) {
          onSelectEdge?.("");
        }
      }} onNodesChange={(changes) => {
        const resized = changes.filter((change): change is NodeDimensionChange => change.type === "dimensions");
        if (resized.length) {
          setMeasurements((current) => {
            let next = current;
            for (const change of resized) {
              const size = change.dimensions;
              if (size && (current[change.id]?.width !== size.width || current[change.id]?.height !== size.height)) {
                next = {...next, [change.id]: size};
              }
            }
            return next;
          });
        }
        const positions = Object.fromEntries(changes.flatMap((change) => change.type === "position" && change.position ? [[change.id, change.position]] : []));
        if (onMove && Object.keys(positions).length) {
          if (changes.some((change) => change.type === "position" && change.dragging)) {
            setDragged((current) => ({...current, ...positions}));
          } else if (changes.some((change) => change.type === "position" && change.dragging === undefined)) {
            onMove(absoluteCanvasPositions(nodes.map((node) => ({...node, position: positions[node.id] ?? node.position})), graph));
          }
        }
        const selections = changes.filter((change): change is NodeSelectionChange => change.type === "select");
        if (selections.length) {
          const next = new Set(chosen);
          selections.forEach((change) => {
            if (change.selected) {
              next.add(change.id);
            } else {
              next.delete(change.id);
            }
          });
          selectMany([...next]);
        }
      }} nodesDraggable={Boolean(onMove) && mode === "select"} nodesConnectable={Boolean(onConnect)}
      edgesReconnectable={Boolean(onReconnect)} reconnectRadius={18} deleteKeyCode={null} snapToGrid snapGrid={[10, 10]} zoomOnDoubleClick={false}
      ariaLabelConfig={{"node.a11yDescription.default": text("按回车选择节点，方向键移动节点，退出键取消选择。"),
        "node.a11yDescription.keyboardDisabled": text("按回车选择节点，退出键取消选择。"),
        "edge.a11yDescription.default": text("按回车选择连接。")}}>
      <Background gap={20} size={1} color="var(--border)"/>
      <NodeToolbar nodeId={chosen} isVisible={chosen.length > 0 && !moving} position={Position.Top} offset={14} className={canvasStyles.quickbar}>
        {selectionActions}
      </NodeToolbar>
      <Panel position="bottom-left" className={canvasStyles.navigation} aria-label={text("画布工具")}>
        <Button type="button" className={canvasStyles.action} aria-label={text("选择工具")} title={text("选择工具（V）")} aria-pressed={mode === "select"} onClick={() => setMode("select")}><IconSelect size={18}/><span>{text("选择")}</span></Button>
        <Button type="button" className={canvasStyles.action} aria-label={text("拖拽工具")} title={text("拖拽工具（H，空格临时平移）")} aria-pressed={mode === "pan"} onClick={() => setMode("pan")}><IconPan size={18}/><span>{text("拖拽")}</span></Button>
        <span className={canvasStyles.divider}/>
        <Button type="button" className={canvasStyles.action} aria-label={text("放大流程图")} onClick={() => void instance?.zoomIn({duration: animationDuration()})}><IconPlus size={18}/><span>{text("放大")}</span></Button>
        <Button type="button" className={canvasStyles.action} aria-label={text("缩小流程图")} onClick={() => void instance?.zoomOut({duration: animationDuration()})}><IconMinus size={18}/><span>{text("缩小")}</span></Button>
        <Button type="button" className={canvasStyles.action} aria-label={text("适应画布")} onClick={fit}><IconMaximize size={18}/><span>{text("适应")}</span></Button>
        <Button type="button" className={canvasStyles.action} aria-label={text("小地图")} aria-pressed={minimap} onClick={() => setMinimap((value) => !value)}><IconMap size={18}/><span>{text("小地图")}</span></Button>
      </Panel>
      {minimap && <MiniMap position="bottom-left" className={canvasStyles.minimap} pannable zoomable
        ariaLabel={text("小地图")} nodeColor={(node) => node.type === "workflowGroup" ? "transparent" : "var(--secondary)"}
        nodeStrokeColor="var(--border)" nodeStrokeWidth={1} maskColor="color-mix(in srgb, var(--surface-soft) 75%, transparent)"
        style={{width: 170, height: 110, bottom: "var(--workflow-minimap-bottom, 72px)", background: "var(--surface)"}}/>}
    </ReactFlow>
    {drop && <div ref={dropMenu} className={canvasStyles.dropMenu} style={{left: drop.screen.x, top: drop.screen.y}} aria-label={text("连接到新节点")}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.stopPropagation();
          setDrop(null);
          canvas.current?.focus();
        }
      }}>
      <div className={canvasStyles.dropHeading}>{text("连接到新节点")}<Button type="button" className={canvasStyles.action} aria-label={text("取消添加节点")} onClick={() => setDrop(null)}><IconX size={16}/></Button></div>
      {nodeChoices.map(({type, description}) => {
        const Icon = workflowIcons[type];
        return <Button key={type} type="button" className={styles.libraryItem} disabled={canAdd && !canAdd(type)} onClick={() => {
          onAdd?.(type, drop.point, drop.origin);
          setDrop(null);
        }}><Icon size={18}/><span><strong>{text(nodeNames[type])}</strong><small>{text(description)}</small></span></Button>;
      })}
    </div>}
  </ContextMenuTrigger><ContextMenuContent className={styles.contextMenu}>
    {contextNode && <><ContextMenuGroup><ContextMenuLabel>{contextNode.name}</ContextMenuLabel>
      <ContextMenuItem onClick={() => onSelect(contextNode.nodeId)}>{text("编辑节点")}</ContextMenuItem>
      {!["start", "end"].includes(contextNode.type) && <>
        <ContextMenuItem onClick={() => onCopy?.([contextNode.nodeId])}><IconCopy size={16}/>{text("复制节点")}</ContextMenuItem>
        <ContextMenuItem variant="destructive" onClick={() => onDelete?.([contextNode.nodeId])}><IconTrash size={16}/>{text("删除节点")}</ContextMenuItem>
      </>}
    </ContextMenuGroup><ContextMenuSeparator/></>}
    {members.length >= 2 && <><ContextMenuItem onClick={() => group ? onUngroup?.(group.groupId) : onGroup?.(members)}>
      <IconStack size={16}/>{text(group ? "取消分组" : "创建分组")}</ContextMenuItem><ContextMenuSeparator/></>}
    <ContextMenuGroup><ContextMenuLabel>{text("添加节点")}</ContextMenuLabel>
      {nodeChoices.map(({type, description}) => {
        const Icon = workflowIcons[type];
        return <ContextMenuItem key={type} disabled={canAdd && !canAdd(type)} className={styles.contextItem}
          onClick={() => onAdd?.(type, context.point)}><Icon size={18}/><span><strong>{text(nodeNames[type])}</strong><small>{text(description)}</small></span>
        </ContextMenuItem>;
      })}
    </ContextMenuGroup><ContextMenuSeparator/>
    <ContextMenuItem onClick={onArrange}><IconLayoutGrid size={16}/>{text("整理布局")}</ContextMenuItem>
  </ContextMenuContent></ContextMenu>;
}
