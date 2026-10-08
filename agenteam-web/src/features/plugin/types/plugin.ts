export type PluginTool = {
  id: string | null;
  name: string;
  displayName?: string;
  description: string;
  operationClass: "read" | "write" | "destructive" | "unknown";
  enabled: boolean;
  supportsDeduplication: boolean;
  supportsResultQuery: boolean;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown> | null;
  entryId?: string | null;
  sourceId?: string | null;
  sourceVersionId?: string | null;
  sourceName?: string | null
};
export type ToolSource = {
  resourceId: string;
  versionId: string;
  versionNo: number;
  name: string;
  description: string;
  icon: string;
  color: string;
  type: "builtin";
  code: string;
  tools: PluginTool[]
};
export type BuiltinPlugin = { code: string; name: string; description: string; toolNames: string[] };
export type ConnectionCheck = {
  checkedAt: string; success: boolean; summary: string; durationMs: number;
  toolChanges: { name: string; change: "added" | "removed" | "changed" | "unchanged" }[]
};
export type CredentialKind = "api_key" | "bearer" | "basic" | "database";
export type Credential = {
  id: string; revision: string; name: string; kind: CredentialKind; status: "active" | "revoked";
  referenceCount: number; createdAt: string; updatedAt: string
};
export type ToolCall = {
  id: string;
  runId: string | null;
  actor: { id: string; displayName: string };
  toolName: string;
  status: "prepared" | "waiting_approval" | "running" | "succeeded" | "failed" | "cancelled" | "unknown";
  operationClass: "read" | "write" | "destructive" | "unknown";
  durationMs: number | null;
  errorSummary: string | null;
  createdAt: string;
  canViewDetails: boolean;
  source: "interactive" | "preview" | "scheduled" | "manual_schedule" | null;
  conversationId: string | null
};
export type ToolCallDetails = {
  call: ToolCall;
  request: Record<string, unknown>;
  result: Record<string, unknown> | null
};
