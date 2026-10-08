export const reasoningEfforts = ["none", "minimal", "low", "medium", "high", "xhigh", "max"] as const;
export type ReasoningEffort = typeof reasoningEfforts[number];

export const reasoningEffortLabels: Record<ReasoningEffort, string> = {
  none: "不思考", minimal: "极低", low: "低", medium: "中", high: "高", xhigh: "极高", max: "最高",
};

export type ModelSelection = { modelProfileId: string; reasoningEffort: ReasoningEffort | null };

export function sameModelSelection(left?: ModelSelection | null, right?: ModelSelection | null) {
  return left?.modelProfileId === right?.modelProfileId && (left?.reasoningEffort ?? null) === (right?.reasoningEffort ?? null);
}

/** 切换模型时保留仍被支持的等级，否则使用新模型的默认设置。 */
export function selectModel(model: {
  id: string;
  reasoningEfforts: ReasoningEffort[]
}, current?: ModelSelection | null): ModelSelection {
  return {
    modelProfileId: model.id,
    reasoningEffort: current?.reasoningEffort && model.reasoningEfforts.includes(current.reasoningEffort) ? current.reasoningEffort : null
  };
}
