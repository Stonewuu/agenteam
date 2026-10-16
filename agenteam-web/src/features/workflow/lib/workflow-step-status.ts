import type {RunStep} from "@/features/agent/types/execution";

/** 循环中可以使用相同节点编号，外层画布只显示本次外层调用的步骤状态。 */
export function workflowNodeSteps(steps: RunStep[], resourceId: string): RunStep[] {
  const root = steps.filter((step) => step.kind === "workflow" && step.workflow?.resourceId === resourceId)
    .sort((left, right) => left.displayOrder - right.displayOrder)[0];
  return root ? steps.filter((step) => step.workflow?.invocationId === root.workflow?.invocationId && step.workflow?.nodeId) : [];
}
