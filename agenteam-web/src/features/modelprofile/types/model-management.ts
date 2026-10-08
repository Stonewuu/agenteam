import type {ReasoningEffort} from "../lib/reasoning-effort";

export type ModelCapabilities = {
  supportsTools: boolean; supportsTemperature: boolean; maxOutputTokens: number;
  maxContextTokens: number; inputTypes: ("text" | "image")[];
  reasoningEfforts?: ReasoningEffort[];
};
export type ModelProvider = {
  id: string; revision: string; name: string; protocol: "openai"; baseUrl: string;
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
