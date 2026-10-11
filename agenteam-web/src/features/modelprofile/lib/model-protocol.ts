import type {ModelProtocol, ModelProtocolOptions} from "../types/model-management";

export const modelProtocols: {value: ModelProtocol; label: string; example: string}[] = [
  {value: "openai", label: "OpenAI Chat Completions", example: "https://api.openai.com/v1"},
  {value: "openai_responses", label: "OpenAI Responses", example: "https://api.openai.com/v1"},
  {value: "anthropic", label: "Anthropic", example: "https://api.anthropic.com"},
  {value: "gemini", label: "Gemini", example: "https://generativelanguage.googleapis.com"},
];

export function defaultProtocolOptions(protocol: ModelProtocol): ModelProtocolOptions {
  return {
    version: 1, reasoningMode: protocol === "anthropic" || protocol === "gemini" ? "budget" : "effort",
    thinkingBudgets: {}, thinkingLevels: {}, cacheTtlSeconds: 0, usageFormat: "standard",
    notApplicableUsage: [], serviceTier: null,
  };
}
