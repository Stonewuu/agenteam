"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {memo, useEffect, useId, useMemo, useRef, useState} from "react";
import {
  Background,
  type Connection,
  ControlButton,
  Controls,
  type Edge,
  Handle,
  MarkerType,
  type Node,
  type NodeProps,
  Position,
  ReactFlow,
  type ReactFlowInstance
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  IconAdjustments,
  IconArrowsJoin,
  IconArrowsSplit,
  IconFlag,
  IconGitBranch,
  IconMaximize,
  IconMinus,
  IconPlayerPlay,
  IconPlug,
  IconPlus,
  IconRobot,
  IconShieldCheck,
  IconSparkles
} from "@/components/ui/icons";
import type {BlockStatus} from "@/features/agent/types/execution";
import type {Branch, NodeType, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {branches, branchNames, nodeNames} from "../lib/workflow-graph";
import styles from "./workflow.module.css";

export const workflowIcons = {
  start: IconPlayerPlay,
  end: IconFlag,
  agent: IconRobot,
  skill: IconSparkles,
  tool: IconPlug,
  condition: IconGitBranch,
  transform: IconAdjustments,
  approval: IconShieldCheck,
  parallel: IconArrowsSplit,
  join: IconArrowsJoin
};
const tones: Record<NodeType, string> = {
  start: "mint",
  end: "mint",
  agent: "purple",
  skill: "purple",
  tool: "blue",
  condition: "amber",
  transform: "blue",
  approval: "amber",
  parallel: "pink",
  join: "pink"
};
const statuses: Record<BlockStatus, string> = {
  pending: "尚未开始",
  running: "正在执行",
  waiting_approval: "等待确认",
  completed: "已完成",
  failed: "未完成",
  cancelled: "已停止",
  skipped: "未执行"
};
type DiagramNode = Node<{ item: WorkflowNode; status?: BlockStatus; invalid: boolean }, "agenteam">;

function summary(node: WorkflowNode, text: (message: string) => string) {
  if (node.type === "start") {
    return text("接收任务输入");
  }
  if (node.type === "end") {
    return text("返回最终结果");
  }
  if (node.type === "tool") {
    return node.config.toolId || node.config.toolName && node.config.toolName !== "待选择" ? text("执行所选工具") : text("请选择工具");
  }
  if (node.type === "approval" && typeof node.config.title === "string") {
    return node.config.title;
  }
  return text("点击配置这个步骤");
}

const DiagramItem = memo(function DiagramItem({data, selected, isConnectable}: NodeProps<DiagramNode>) {
  const uiText = useT();
  const node = data.item;
  const Icon = workflowIcons[node.type];
  const outgoing = branches(node.type);
  return <div
    className={`${styles.node} ${selected ? styles.selectedNode : ""} ${data.invalid ? styles.invalidNode : ""}`}
    data-status={data.status}>
    {node.type !== "start" && <Handle type="target" position={Position.Left} isConnectable={isConnectable}
                                      aria-label={uiText("{0}的输入连接点", [node.name])}/>}
    <div className={styles.nodeHeading}><span className={`avatar small ${tones[node.type]}`}><Icon size={19}/></span>
      <div><strong>{node.name}</strong><small>{localizeCatalog(nodeNames, uiText)[node.type]}</small></div>
    </div>
    <p>{data.status ? localizeCatalog(statuses, uiText)[data.status] : summary(node, uiText)}</p>
    {outgoing.map((branch, index) => <Handle key={branch} id={branch} type="source" position={Position.Right}
                                             isConnectable={isConnectable}
                                             style={{top: `${(index + 1) * 100 / (outgoing.length + 1)}%`}}
                                             title={localizeCatalog(branchNames, uiText)[branch]}
                                             aria-label={uiText("{0}的{1}连接点", [node.name, localizeCatalog(branchNames, uiText)[branch]])}/>)}
  </div>;
});
const nodeTypes = {agenteam: DiagramItem};
const animationDuration = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 180;

export function WorkflowGraphCanvas({
                                      graph,
                                      selected,
                                      onSelect,
                                      onMove,
                                      onConnect,
                                      selectedEdge,
                                      onSelectEdge,
                                      nodeStatuses = {},
                                      invalidNodes = []
                                    }: {
  graph: WorkflowGraph;
  selected?: string;
  onSelect: (id: string) => void;
  onMove?: (id: string, position: WorkflowNode["position"]) => void;
  onConnect?: (source: string, target: string, branch: Branch) => void;
  selectedEdge?: string;
  onSelectEdge?: (id: string) => void;
  nodeStatuses?: Record<string, BlockStatus>;
  invalidNodes?: string[];
}) {
  const uiText = useT();
  const canvas = useRef<HTMLDivElement>(null);
  const [instance, setInstance] = useState<ReactFlowInstance<DiagramNode, Edge> | null>(null);
  const flowId = useId();
  const nodes = useMemo<DiagramNode[]>(() => graph.nodes.map((node) => ({
    id: node.nodeId,
    type: "agenteam",
    position: node.position,
    selected: selected === node.nodeId,
    width: 205,
    height: 112,
    ariaLabel: `${node.name}，${localizeCatalog(nodeNames, uiText)[node.type]}`,
    ariaRole: "button",
    data: {item: node, status: nodeStatuses[node.nodeId], invalid: invalidNodes.includes(node.nodeId)}
  })), [graph.nodes, selected, nodeStatuses, invalidNodes, uiText]);
  const edges: Edge[] = graph.edges.map((edge) => ({
    id: edge.edgeId,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.branch,
    type: "smoothstep",
    selected: edge.edgeId === selectedEdge,
    label: edge.branch !== "default" ? localizeCatalog(branchNames, uiText)[edge.branch] : undefined,
    markerEnd: {type: MarkerType.ArrowClosed, color: "var(--accent)"},
    style: {stroke: "var(--accent)", strokeWidth: 1.6},
    labelStyle: {fill: "var(--secondary)", fontSize: 11},
    labelBgStyle: {fill: "var(--surface)"},
    ariaLabel: uiText("{0}到{1}，{2}", [graph.nodes.find((node) => node.nodeId === edge.source)?.name, graph.nodes.find((node) => node.nodeId === edge.target)?.name, localizeCatalog(branchNames, uiText)[edge.branch]])
  }));
  useEffect(() => {
    if (!instance || !canvas.current) {
      return;
    }
    let frame = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        void instance.fitView({padding: .2, maxZoom: 1, duration: animationDuration()});
      });
    });
    observer.observe(canvas.current);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [instance]);
  useEffect(() => {
    if (instance) {
      void instance.fitView({padding: .2, maxZoom: 1, duration: animationDuration()});
    }
  }, [instance, graph.nodes.length]);
  const connect = (connection: Connection) => {
    if (connection.source && connection.target) {
      onConnect?.(connection.source, connection.target, (connection.sourceHandle as Branch) || "default");
    }
  };
  return <div className={styles.canvasFrame} ref={canvas} aria-label={uiText("流程画布")}>
    <ReactFlow<DiagramNode, Edge> id={flowId} nodes={nodes} edges={edges} nodeTypes={nodeTypes} onInit={setInstance}
                                  fitView fitViewOptions={{padding: .2, maxZoom: 1}} minZoom={.2} maxZoom={1.6}
                                  proOptions={{hideAttribution: true}}
                                  onNodeClick={(_, node) => {
                                    onSelectEdge?.("");
                                    onSelect(node.id);
                                  }} onPaneClick={() => {
      onSelect("");
      onSelectEdge?.("");
    }}
                                  onEdgeClick={(_, edge) => {
                                    onSelect("");
                                    onSelectEdge?.(edge.id);
                                  }} onConnect={connect}
                                  onNodesChange={(changes) => {
                                    for (const change of changes) {
                                      if (change.type === "position" && change.position && onMove) {
                                        onMove(change.id, change.position);
                                      }
                                    }
                                    const chosen = changes.find((change) => change.type === "select" && change.selected);
                                    if (chosen && "id" in chosen) {
                                      onSelect(chosen.id);
                                    } else if (changes.some((change) => change.type === "select" && !change.selected && change.id === selected)) {
                                      onSelect("");
                                    }
                                  }}
                                  nodesDraggable={Boolean(onMove)} nodesConnectable={Boolean(onConnect)}
                                  edgesReconnectable={false} deleteKeyCode={null} multiSelectionKeyCode={null}
                                  selectionKeyCode={null} snapToGrid snapGrid={[10, 10]} zoomOnDoubleClick={false}
                                  ariaLabelConfig={{
                                    "node.a11yDescription.default": uiText("按回车选择节点，方向键移动节点，退出键取消选择。"),
                                    "node.a11yDescription.keyboardDisabled": uiText("按回车选择节点，退出键取消选择。"),
                                    "node.a11yDescription.ariaLiveMessage": ({direction}) => uiText("已向{0}移动节点。", [({
                                      left: uiText("左"),
                                      right: uiText("右"),
                                      up: uiText("上"),
                                      down: uiText("下")
                                    } as Record<string, string>)[direction] ?? uiText("所选方向")]),
                                    "edge.a11yDescription.default": uiText("按回车选择连接。"),
                                    "controls.ariaLabel": uiText("画布缩放")
                                  }}>
      <Background gap={20} size={1} color="var(--border)"/>
      <Controls showZoom={false} showFitView={false} showInteractive={false}>
        <ControlButton type="button" aria-label={uiText("放大流程图")} title={uiText("放大")}
                       onClick={() => void instance?.zoomIn({duration: animationDuration()})}><IconPlus
          size={18}/></ControlButton>
        <ControlButton type="button" aria-label={uiText("缩小流程图")} title={uiText("缩小")}
                       onClick={() => void instance?.zoomOut({duration: animationDuration()})}><IconMinus
          size={18}/></ControlButton>
        <ControlButton type="button" aria-label={uiText("适应画布")} title={uiText("适应画布")}
                       onClick={() => void instance?.fitView({padding: .2, maxZoom: 1, duration: animationDuration()})}><IconMaximize
          size={18}/></ControlButton>
      </Controls>
    </ReactFlow>
  </div>;
}
