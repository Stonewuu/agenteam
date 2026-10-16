import {IconAdjustments, IconArrowsJoin, IconArrowsSplit, IconFlag, IconGitBranch, IconPlayerPlay,
  IconPlug, IconRefresh, IconRobot, IconShieldCheck, IconSparkles} from "@/components/ui/icons";
import type {NodeType} from "../types/workflow";

export const workflowIcons = {start: IconPlayerPlay, end: IconFlag, agent: IconRobot, skill: IconSparkles,
  tool: IconPlug, condition: IconGitBranch, transform: IconAdjustments, approval: IconShieldCheck,
  parallel: IconArrowsSplit, join: IconArrowsJoin, loop: IconRefresh};
export type AddableNode = Exclude<NodeType, "start" | "end">;
export const nodeChoices: {type: AddableNode; description: string; group: string}[] = [
  {type: "agent", description: "让智能体完成一个步骤", group: "执行"},
  {type: "tool", description: "调用插件提供的能力", group: "执行"},
  {type: "skill", description: "交给智能体执行指定技能", group: "执行"},
  {type: "condition", description: "按条件进入不同分支", group: "流程"},
  {type: "loop", description: "逐项处理或重复执行步骤", group: "流程"},
  {type: "parallel", description: "同时执行多路步骤", group: "流程"},
  {type: "join", description: "等待并行分支完成后继续", group: "流程"},
  {type: "approval", description: "等待人工确认后继续", group: "流程"},
  {type: "transform", description: "选择、组合和转换数据", group: "数据"},
];
