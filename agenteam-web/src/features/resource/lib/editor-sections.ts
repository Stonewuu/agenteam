import type {ResourceKind} from "../types/resource";

export type EditorSection =
  "basic"
  | "capabilities"
  | "experience"
  | "advanced"
  | "content"
  | "connection"
  | "documents"
  | "collections"
  | "workflow";
export const editorSections: Record<ResourceKind, { value: EditorSection; label: string }[]> = {
  agent: [{value: "basic", label: "基础配置"}, {value: "capabilities", label: "能力与资料"}, {
    value: "experience",
    label: "对话体验"
  }, {value: "advanced", label: "高级设置"}],
  skill: [{value: "basic", label: "基础信息"}, {value: "content", label: "技能内容"}, {
    value: "capabilities",
    label: "能力依赖"
  }],
  plugin: [{value: "basic", label: "基础信息"}, {value: "connection", label: "工具与连接"}],
  workflow: [{value: "basic", label: "基础信息"}, {value: "workflow", label: "流程编排"}],
  knowledge: [{value: "basic", label: "基础配置"}, {value: "documents", label: "文档管理"}],
  data: [{value: "basic", label: "基础配置"}, {value: "collections", label: "集合与查询"}],
};

export function sectionForField(kind: ResourceKind, name: string): EditorSection {
  if (!name.startsWith("config.")) {
    return "basic";
  }
  const field = name.slice(7);
  if (kind === "agent") {
    if (/VersionIds|entryWorkflowVersionId/.test(field)) {
      return "capabilities";
    }
    if (/^(welcomeMessage|suggestedQuestions|publicExamples|attachmentsEnabled|historyMessageLimit)/.test(field)) {
      return "experience";
    }
    if (/^(temperature|maxSteps|timeoutSeconds|memory|businessTerms|subagent|dynamicSubagent)/.test(field)) {
      return "advanced";
    }
  }
  if (kind === "skill") {
    return /VersionIds/.test(field) ? "capabilities" : /^(icon|color)/.test(field) ? "basic" : "content";
  }
  if (kind === "plugin" && !/^(icon|color)/.test(field)) {
    return "connection";
  }
  if (kind === "workflow" && !/^(icon|color)/.test(field)) {
    return "workflow";
  }
  return "basic";
}
