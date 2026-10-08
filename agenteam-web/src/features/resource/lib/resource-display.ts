import type {
  AgentConfig,
  DraftWrite,
  ResourceConfig,
  ResourceDetail,
  ResourceKind,
  ResourceSummary
} from "../types/resource";
import {randomResourceAppearance} from "@/components/ui/resource-appearance";
import {agentConfigInput} from "./agent-config-input";
import {initialGraph} from "@/features/workflow/lib/workflow-graph";

export const resourceTypes: { kind: ResourceKind; path: string; label: string }[] = [
  {kind: "agent", path: "agents", label: "智能体"}, {kind: "skill", path: "skills", label: "技能"}, {
    kind: "plugin",
    path: "plugins",
    label: "插件"
  },
  {kind: "workflow", path: "workflows", label: "工作流"}, {
    kind: "knowledge",
    path: "knowledge",
    label: "知识库"
  }, {kind: "data", path: "data", label: "数据源"},
];
export const resourceSubtypes: Partial<Record<ResourceKind, [string, string][]>> = {
  agent: [["chat", "对话型"], ["task", "任务型"], ["workflow", "流程型"]],
  plugin: [["collection", "工具合集"], ["mcp", "远程插件"], ["builtin", "内置插件"]],
  data: [["file", "文件"], ["mysql", "MySQL 数据库"], ["http", "远程接口"]],
};

export function resourceLabel(kind: ResourceKind) {
  return resourceTypes.find((value) => value.kind === kind)!.label;
}

export function resourcePage(enterpriseId: string, kind: ResourceKind, id?: string) {
  return `/enterprises/${encodeURIComponent(enterpriseId)}/capabilities/${resourceTypes.find((value) => value.kind === kind)!.path}${id ? `/${encodeURIComponent(id)}` : ""}`;
}

export function resourceStatus(resource: ResourceSummary) {
  if (resource.status === "deleted") {
    return "已删除";
  }
  if (resource.status === "disabled") {
    return "已停用";
  }
  if (!resource.publishedVersion) {
    return "草稿";
  }
  if (resource.publishedVersion.status === "revoked") {
    return "当前版本已撤销";
  }
  return "已发布";
}

export function draftFrom(detail: ResourceDetail): DraftWrite {
  return draftInput(detail.resource.kind, {
    name: detail.resource.name, description: detail.resource.description,
    tagIds: detail.resource.tags.map((tag) => tag.id), config: structuredClone(detail.draft)
  });
}

export function draftInput(kind: ResourceKind, draft: DraftWrite): DraftWrite {
  return {...draft, config: kind === "agent" ? agentConfigInput(draft.config as AgentConfig) : draft.config};
}

export function newConfig(kind: ResourceKind): ResourceConfig {
  const appearance = randomResourceAppearance();
  switch (kind) {
    case "agent":
      return {
        ...appearance,
        agentType: "chat",
        businessRole: "",
        modelProfileId: null,
        instructions: "",
        maxSteps: 0,
        timeoutSeconds: 0,
        attachmentsEnabled: true,
        welcomeMessage: "",
        suggestedQuestions: [],
        skillVersionIds: [],
        pluginVersionIds: [],
        knowledgeVersionIds: [],
        dataVersionIds: [],
        workflowVersionIds: [],
        entryWorkflowVersionId: null,
        historyMessageLimit: 20,
        memoryEnabled: false,
        memoryFields: [],
        businessTerms: [],
        subagentVersionIds: [],
        dynamicSubagentEnabled: false,
        publicExamples: []
      };
    case "skill":
      return {
        ...appearance,
        scenario: "",
        inputDescription: "",
        instructions: "",
        outputDescription: "",
        example: "",
        pluginVersionIds: [],
        knowledgeVersionIds: [],
        showInWorkspace: false
      };
    case "knowledge":
      return {...appearance, description: "", retrievalMode: "keyword", maxResults: 5, maxContextCharacters: 5000};
    case "plugin":
      return {...appearance, sources: [], tools: [], timeoutSeconds: 30};
    case "data":
      return {
        ...appearance,
        sourceType: "file",
        credentialId: null,
        connection: {},
        timeoutSeconds: 5,
        readOnly: true,
        updateMode: "manual"
      };
    case "workflow":
      return {...appearance, ...initialGraph()};
  }
}
