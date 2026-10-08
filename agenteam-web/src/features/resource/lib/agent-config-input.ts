import type {AgentConfig} from "../types/resource";

/** 提交当前表单声明的字段，不把读取结果中的额外属性作为编辑内容发回。 */
export function agentConfigInput(value: AgentConfig): AgentConfig {
  return {
    icon: value.icon,
    color: value.color,
    agentType: value.agentType,
    businessRole: value.businessRole,
    modelProfileId: value.modelProfileId,
    instructions: value.instructions,
    ...(value.reasoningEffort == null ? {} : {reasoningEffort: value.reasoningEffort}),
    ...(value.temperature === undefined ? {} : {temperature: value.temperature}),
    maxSteps: value.maxSteps,
    timeoutSeconds: value.timeoutSeconds,
    attachmentsEnabled: value.attachmentsEnabled,
    welcomeMessage: value.welcomeMessage,
    suggestedQuestions: value.suggestedQuestions,
    skillVersionIds: value.skillVersionIds,
    pluginVersionIds: value.pluginVersionIds,
    knowledgeVersionIds: value.knowledgeVersionIds,
    dataVersionIds: value.dataVersionIds,
    workflowVersionIds: value.workflowVersionIds,
    entryWorkflowVersionId: value.entryWorkflowVersionId,
    historyMessageLimit: value.historyMessageLimit,
    memoryEnabled: value.memoryEnabled,
    memoryFields: value.memoryFields,
    businessTerms: value.businessTerms,
    subagentVersionIds: value.subagentVersionIds ?? [],
    dynamicSubagentEnabled: value.dynamicSubagentEnabled ?? false,
    publicExamples: value.publicExamples,
  };
}
