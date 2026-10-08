import type {PluginTool} from "@/features/plugin/types/plugin";
import type {UsableVersion} from "@/features/resource/types/resource";

export type NodeType =
  "start"
  | "agent"
  | "skill"
  | "tool"
  | "condition"
  | "transform"
  | "approval"
  | "parallel"
  | "join"
  | "end";
export type Branch = "default" | "true" | "false" | "approve" | "reject";
export type WorkflowNode = {
  nodeId: string; name: string; type: NodeType; position: { x: number; y: number }; timeoutSeconds: number;
  failurePolicy: "stop" | "continue"; config: Record<string, unknown>
};
export type WorkflowEdge = { edgeId: string; source: string; target: string; branch: Branch };
export type WorkflowGraph = { nodes: WorkflowNode[]; edges: WorkflowEdge[] };
export type WorkflowValidation = {
  valid: boolean;
  errors: { nodeId: string | null; edgeId: string | null; field: string; code: string; message: string }[]
};
export type DependencyOptions = {
  kind: "agent" | "plugin";
  agentType: "chat" | "task" | "workflow" | null;
  skills: UsableVersion[];
  tools: PluginTool[]
};
export type Comparison = {
  field: string;
  operator: "eq" | "ne" | "gt" | "gte" | "lt" | "lte" | "in" | "contains" | "exists";
  value?: unknown
};
export type Condition = Comparison | { all: Condition[] } | { any: Condition[] } | { not: Condition };
export type TransformField = { target: string; source?: string; template?: string; literal?: unknown };
