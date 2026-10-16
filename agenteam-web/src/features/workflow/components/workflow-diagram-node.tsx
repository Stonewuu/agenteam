"use client";

import {memo} from "react";
import {Handle, Position, type Node, type NodeProps} from "@xyflow/react";
import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";
import type {BlockStatus} from "@/features/agent/types/execution";
import type {Branch, NodeType, WorkflowNode} from "../types/workflow";
import {branches, branchNames, nodeNames} from "../lib/workflow-graph";
import {workflowIcons} from "./workflow-node-catalog";
import styles from "./workflow.module.css";
import canvasStyles from "./workflow-canvas.module.css";

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
  join: "pink",
  loop: "blue"
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
export type DiagramNode = Node<{ item: WorkflowNode; status?: BlockStatus; invalid: boolean; connectedBranches: Branch[] }, "agenteam">;

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
  if (node.type === "loop") {
    return node.config.mode === "items" ? text("逐项处理列表") : `${text("重复执行")} · ${node.config.count}`;
  }
  return text("点击配置这个步骤");
}

export const DiagramItem = memo(function DiagramItem({data, selected, isConnectable}: NodeProps<DiagramNode>) {
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
    {outgoing.length > 1 && outgoing.map((branch, index) => !data.connectedBranches.includes(branch) && <span key={branch} className={canvasStyles.portLabel}
      style={{top: `${(index + 1) * 100 / (outgoing.length + 1)}%`}}>{uiText(branchNames[branch])}</span>)}
  </div>;
});
export type GroupNode = Node<{name: string}, "workflowGroup">;
export function DiagramGroup({data, selected}: NodeProps<GroupNode>) {
  return <div className={canvasStyles.group} data-selected={selected}>
    <span className={canvasStyles.groupTitle}>{data.name}</span>
  </div>;
}
