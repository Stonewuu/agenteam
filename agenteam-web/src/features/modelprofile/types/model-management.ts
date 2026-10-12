import type {ReasoningEffort} from "../lib/reasoning-effort";

export type ModelProtocol = "openai" | "openai_responses" | "anthropic" | "gemini";
export type ModelProtocolOptions = {
  version: 1;
  reasoningMode: "effort" | "budget" | "adaptive" | "level";
  thinkingBudgets: Partial<Record<ReasoningEffort, number>>;
  thinkingLevels: Partial<Record<ReasoningEffort, "minimal" | "low" | "medium" | "high" | "max">>;
  cacheTtlSeconds: number;
  usageFormat: "standard" | "deepseek";
  notApplicableUsage: ("cache_read" | "cache_write" | "reasoning" | "tool_use_prompt")[];
  serviceTier: string | null;
};

export type ModelCapabilities = {
  supportsTools: boolean; supportsTemperature: boolean; maxOutputTokens: number;
  maxContextTokens: number; inputTypes: ("text" | "image")[];
  reasoningEfforts?: ReasoningEffort[];
  protocolOptions?: ModelProtocolOptions;
};
export type ModelProvider = {
  id: string; revision: string; name: string; protocol: ModelProtocol; baseUrl: string;
  keyConfigured: boolean; enabled: boolean; modelCount: number; inUse: boolean;
  createdAt: string; updatedAt: string;
};
export type ManagedModel = {
  id: string; revision: string; providerId: string; providerName: string; providerEnabled: boolean;
  name: string; modelName: string; capabilities: ModelCapabilities; enabled: boolean; inUse: boolean;
  createdAt: string; updatedAt: string;
};
export type RemoteModel = {
  id: string;
  name: string;
  maxContextTokens: number | null;
  maxOutputTokens: number | null;
  inputTypes: ModelCapabilities["inputTypes"] | null;
  supportsTools: boolean | null;
  supportsTemperature: boolean | null;
};
