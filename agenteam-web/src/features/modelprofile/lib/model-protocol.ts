import type {ModelProtocol, ModelProtocolOptions, NativeThinkingLevel} from "../types/model-management";
import type {ReasoningEffort} from "./reasoning-effort";

export const modelProtocols: {value: ModelProtocol; label: string; example: string}[] = [
  {value: "openai", label: "OpenAI Chat Completions", example: "https://api.openai.com/v1"},
  {value: "openai_responses", label: "OpenAI Responses", example: "https://api.openai.com/v1"},
  {value: "anthropic", label: "Anthropic", example: "https://api.anthropic.com"},
  {value: "gemini", label: "Gemini", example: "https://generativelanguage.googleapis.com"},
];

export const nativeThinkingLevels: Record<"level" | "adaptive", readonly NativeThinkingLevel[]> = {
  level: ["minimal", "low", "medium", "high", "xhigh"],
  adaptive: ["low", "medium", "high", "xhigh", "max"],
};

export function defaultProtocolOptions(protocol: ModelProtocol, saved?: ModelProtocolOptions): ModelProtocolOptions {
  const defaultMode = protocol === "gemini" ? "level" : protocol === "anthropic" ? "adaptive" : "effort";
  const useSavedBudget = defaultMode !== "effort" && saved?.reasoningMode === "budget";
  return {
    version: 1, thinkingBudgets: {}, thinkingLevels: {}, cacheTtlSeconds: 0, usageFormat: "standard",
    notApplicableUsage: [], serviceTier: null, ...saved,
    reasoningMode: useSavedBudget ? "budget" : defaultMode,
  };
}

/** 只为勾选的档位补默认值，保留其他档位和思考方式中已有的手动配置。 */
export function resolveProtocolOptions(value: ModelProtocolOptions, efforts: ReasoningEffort[]): ModelProtocolOptions {
  if (value.reasoningMode === "effort") {
    return {...value, thinkingBudgets: {}, thinkingLevels: {}};
  }
  // 未启用的预算只保留可保存的整数，正在编辑的预算仍交给对应字段校验。
  const thinkingBudgets = Object.fromEntries(Object.entries(value.thinkingBudgets)
    .filter(([, budget]) => Number.isInteger(budget) && budget >= 0 && budget <= 1_000_000));
  const thinkingLevels = {...value.thinkingLevels};
  delete thinkingLevels.none;
  for (const effort of efforts) {
    if (value.reasoningMode === "budget") {
      const budget = effort === "none" ? 0 : value.thinkingBudgets[effort];
      if (budget !== undefined) {
        thinkingBudgets[effort] = budget;
      }
    } else if (effort !== "none") {
      let defaultLevel = effort;
      if (effort === "max" && value.reasoningMode === "level") {
        defaultLevel = "xhigh";
      } else if (effort === "minimal" && value.reasoningMode === "adaptive") {
        defaultLevel = "low";
      }
      thinkingLevels[effort] = value.thinkingLevels[effort] ?? defaultLevel;
    }
  }
  return {...value, thinkingBudgets, thinkingLevels};
}
