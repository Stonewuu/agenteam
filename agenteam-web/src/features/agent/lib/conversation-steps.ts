import type {DisplayBlock, DisplayMessage} from "../types/conversation-display";
import type {RunAttempt, RunStep} from "../types/execution";
import {localizeSavedToolLabel} from "../../plugin/lib/tool-display-name";

export type LiveRunStep = { runId: string; messageId: string; sequence: string; step: RunStep };
export type RunStepHistory = { steps: RunStep[]; attempts: RunAttempt[]; sequence: string; error: string };

/** 按输出消息匹配尝试；晚到的历史查询不能覆盖更新后的步骤。 */
export function messageRunSteps(message: DisplayMessage, history: RunStepHistory | undefined, live: ReadonlyMap<string, LiveRunStep> | undefined) {
  const attempt = history?.attempts.find((value) => value.outputMessageId === message.id);
  const result = new Map(history?.steps.filter((step) => step.attemptId === attempt?.id).map((step) => [step.id, step]));
  for (const value of live?.values() ?? []) {
    if (value.runId !== message.runId || value.messageId !== message.id) {
      continue;
    }
    if (!result.has(value.step.id) || !history || BigInt(value.sequence) > BigInt(history.sequence)) {
      result.set(value.step.id, value.step);
    }
  }
  return [...result.values()].sort((a, b) => a.displayOrder - b.displayOrder);
}

/** 内容块保持原父子关系；独立步骤进入最近的实际父步骤，已有内容不重复包裹。 */
export function mergeExecutionSteps(blocks: DisplayBlock[], steps: RunStep[]): DisplayBlock[] {
  const byId = new Map(steps.map((step) => [step.id, step]));
  const represented = new Map<string, DisplayBlock>();
  const copy = (values: DisplayBlock[]): DisplayBlock[] => values.map((value) => {
    const step = value.stepId ? byId.get(value.stepId) : undefined;
    const next = {...value, step, blocks: copy(value.blocks)};
    if (step && !represented.has(step.id)) {
      represented.set(step.id, next);
    }
    return next;
  });
  const result = copy(blocks);
  const added: Array<{ step: RunStep; block: DisplayBlock }> = [];
  for (const step of steps) {
    if (represented.has(step.id)) {
      continue;
    }
    // 最外层智能体是当前回复本身，没有额外摘要时不重复显示“处理任务”。
    if (step.kind === "agent" && step.parentStepId === null && !step.publicSummary) {
      continue;
    }
    // 模型开始和结束由真实思考、正文体现，不能为每次调用再生成一张空卡片。
    if (step.kind === "model" && !step.publicSummary && ["pending", "running", "completed"].includes(step.status)) {
      continue;
    }
    const block: DisplayBlock = {
      kind: "step",
      id: `step:${step.id}`,
      stepId: step.id,
      step,
      order: step.displayOrder,
      label: step.kind === "tool" ? localizeSavedToolLabel(step.title) : step.title,
      status: step.status,
      summary: step.publicSummary ?? "",
      blocks: []
    };
    represented.set(step.id, block);
    added.push({step, block});
  }
  for (const {step, block} of added) {
    let parent = step.parentStepId;
    const visited = new Set([step.id]);
    let target: DisplayBlock | undefined;
    while (parent && !visited.has(parent)) {
      visited.add(parent);
      target = represented.get(parent);
      if (target) {
        break;
      }
      parent = byId.get(parent)?.parentStepId ?? null;
    }
    (target && target !== block ? target.blocks : result).push(block);
  }
  return result;
}
