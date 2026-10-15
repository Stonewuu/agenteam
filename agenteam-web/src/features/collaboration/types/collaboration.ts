export type CollaborationTask = {
  id: string; shortCode: string; title: string; conversationId: string; status: string; revision: number; inputRevision: number;
  outputVisibility: "summary_link" | "chat_full"; createdAt: string; updatedAt: string; deadlineAt: string; finishedAt: string | null; errorCode: string | null;
  source?: {platformCode: string; kind: string; name: string | null} | null;
  roles?: {id: string; name: string; conversationId: string | null}[];
  primaryConversationId?: string | null;
};
export type CollaborationArtifact = {id: string; version: number; kind: "text" | "json" | "file"; title: string; summary: string; inputRevision: number; summaryOnly: boolean};
export type CollaborationDetail = {
  task: CollaborationTask; requirements: {text: string; additionalRequirements?: {text: string}[]}; ownerWorkId: string; ownerRoleId: string; fullOutputAllowed: boolean;
  roles: {id: string; botId: string; name: string; description: string; explicitlyRequested: boolean; availability: string; conversationId: string | null}[];
  works: {id: string; roleId: string; purpose: string; status: string; required: boolean; inputRevision: number; runRound: number; activeRunId: string | null; errorCode: string | null; replacementWorkId: string | null}[];
  artifacts: CollaborationArtifact[];
  questions: {id: string; workId: string; inputRevision: number; text: string; options: string[]; createdAt: string}[];
  deliveries: {id: string; botId: string; kind: string; status: string; errorCode: string | null; createdAt: string}[];
};

export const collaborationPath = (enterprise: string) => `/api/v1/enterprises/${encodeURIComponent(enterprise)}/collaboration/tasks`;
export const collaborationStatus: Record<string, string> = {
  active: "正在处理", ready: "等待开始", running: "正在处理", waiting_input: "等待补充要求", waiting_approval: "等待确认操作",
  waiting_dependency: "等待协作成果", blocked: "需要处理", completed: "已完成", failed: "执行失败", cancelled: "已取消", cancelling: "正在停止",
  handed_off: "已交接", superseded: "已替换",
};
export const collaborationErrors: Record<string, string> = {
  TOOL_RESULT_UNKNOWN: "有外部操作的结果尚未确认，请先核实操作结果。",
  TASK_INPUT_CHANGED: "任务要求已更新，等待负责人重新安排。",
  COLLABORATION_ROLE_ASSIGNMENT_REQUIRED: "部分指定岗位尚未分工，请补充分工要求。",
  COLLABORATION_HANDOFF_CLARIFICATION: "岗位多次互相移交，请明确下一步的负责人和所需成果。",
  ROLE_UNAVAILABLE: "此岗位目前无法使用，请检查智能体权限和雇佣关系。",
};
