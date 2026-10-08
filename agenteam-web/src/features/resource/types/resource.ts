export type ResourceKind = "agent" | "skill" | "plugin" | "workflow" | "knowledge" | "data";
import type {ResourceColor} from "@/components/ui/resource-appearance";
import type {ModelCapabilities} from "@/features/modelprofile/types/model-management";
import type {ReasoningEffort} from "@/features/modelprofile/lib/reasoning-effort";
import type {ConnectionCheck} from "@/features/plugin/types/plugin";
import type {WorkflowGraph} from "@/features/workflow/types/workflow";

export type Appearance = { icon: string; color: ResourceColor };
export type AgentConfig = Appearance & {
  agentType: "chat" | "task" | "workflow";
  businessRole: string;
  modelProfileId: string | null;
  instructions: string;
  temperature?: number;
  reasoningEffort?: ReasoningEffort | null;
  maxSteps: number;
  timeoutSeconds: number;
  attachmentsEnabled: boolean;
  welcomeMessage: string;
  suggestedQuestions: string[];
  skillVersionIds: string[];
  pluginVersionIds: string[];
  knowledgeVersionIds: string[];
  dataVersionIds: string[];
  workflowVersionIds: string[];
  entryWorkflowVersionId: string | null;
  historyMessageLimit: number;
  memoryEnabled: boolean;
  memoryFields: string[];
  businessTerms: { term: string; meaning: string }[];
  subagentVersionIds?: string[];
  dynamicSubagentEnabled?: boolean;
  publicExamples: string[];
};
export type SkillConfig = Appearance & {
  scenario: string; inputDescription: string; instructions: string; outputDescription: string;
  example: string; pluginVersionIds: string[]; knowledgeVersionIds: string[]; showInWorkspace: boolean
};
export type KnowledgeConfig = Appearance & {
  description: string;
  retrievalMode: "keyword";
  maxResults: number;
  maxContextCharacters: number
};
export type PluginSource = {
  id: string;
  type: "builtin" | "mcp";
  versionId?: string | null;
  transport?: "streamable_http" | "legacy_sse" | null;
  endpoint?: string | null;
  credentialId?: string | null
};
export type PluginToolSelection = { id: string; sourceId: string; name: string; timeoutSeconds?: number | null };
export type PluginConfig = Appearance & {
  sources: PluginSource[];
  tools: PluginToolSelection[];
  timeoutSeconds: number
};
export type DataConfig = Appearance & {
  sourceType: "file" | "mysql" | "http"; credentialId: string | null; connection: {
    host?: string; port?: number; database?: string; endpoint?: string; queryParameters?: string[]
  }; timeoutSeconds: number; readOnly: true; updateMode: "manual"
};
export type WorkflowConfig = Appearance & WorkflowGraph;
export type ResourceConfig = AgentConfig | SkillConfig | KnowledgeConfig | PluginConfig | DataConfig | WorkflowConfig;
export type VersionSummary = {
  id: string; versionNo: number; name: string; releaseNote: string; status: "available" | "revoked";
  publishedBy: { id: string; displayName: string }; publishedAt: string
};
export type Tag = { id: string; name: string; revision: string; createdAt: string; updatedAt: string };
export type Grant = {
  subjectType: "enterprise" | "team" | "user";
  subjectId: string;
  capability: "view" | "use" | "edit"
};
export type Listing = { listed: boolean; hirePolicy: "automatic" | "approval" };
export type ResourceSummary = {
  id: string;
  revision: string;
  createdAt: string;
  updatedAt: string;
  kind: ResourceKind;
  name: string;
  description: string;
  icon: string;
  color: string;
  owner: { id: string; displayName: string };
  subtype: string;
  source: "created" | "imported" | "builtin";
  status: "active" | "disabled" | "deleted";
  publishedVersion: VersionSummary | null;
  hasUnpublishedChanges: boolean;
  tags: Tag[];
  allowedActions: string[];
  listing: Listing | null
};
export type ResourceDetail = {
  resource: ResourceSummary; draft: ResourceConfig; grants: Grant[]; fieldErrors: Record<string, string[]>;
  connectionCheck: ConnectionCheck | null
};
export type DraftWrite = { name: string; description: string; tagIds: string[]; config: ResourceConfig };
export type ResourceVersion = {
  version: VersionSummary;
  config: ResourceConfig;
  dependencies: { resourceId: string; versionId: string; kind: ResourceKind; name: string; bindingKey: string }[]
};
export type UsableVersion = {
  resourceId: string;
  versionId: string;
  versionNo: number;
  kind: ResourceKind;
  name: string;
  description: string;
  icon: string;
  color: string
};
export type ModelProfile = {
  id: string;
  name: string;
  modelName: string;
  enabled: boolean;
  capabilities: ModelCapabilities
};
export type ResourceImpact = {
  resourceId: string;
  revision: string;
  visibleDependencies: { id: string; kind: ResourceKind; name: string }[];
  hiddenDependencyCount: number;
  activeHireCount: number;
  activeRunCount: number;
  enabledScheduleCount: number;
  canDelete: boolean
};
export type SubjectOption = { id: string; name: string; subjectType: "team" | "user"; active: boolean };
