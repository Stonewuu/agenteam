export type TodoStatus = "pending" | "in_progress" | "completed" | "cancelled";
export type TodoAction = "edit" | "transfer" | "delete" | "start" | "complete" | "cancel" | "reopen";
export type TodoOption = { id: string; name: string };
export type TodoActor = { id: string; displayName: string };
export type TodoSource = {
  sourceType: "manual" | "message" | "workflow";
  sourceConversationId: string | null;
  sourceMessageId: string | null;
  sourceRunId: string | null
};
export type TodoDraftSource = TodoSource & { text: string };
export type TodoWrite = TodoSource & {
  title: string;
  description: string;
  ownerUserId: string;
  teamId: string | null;
  dueDate: string | null;
  priority: "normal" | "high"
};
export type Todo = {
  id: string;
  revision: string;
  title: string;
  description: string;
  owner: TodoActor;
  createdBy: TodoActor;
  teamId: string | null;
  teamName: string | null;
  dueDate: string | null;
  priority: "normal" | "high";
  status: TodoStatus;
  sourceType: TodoSource["sourceType"];
  sourceAccessible: boolean;
  sourceConversationId: string | null;
  completedAt: string | null;
  createdAt: string;
  updatedAt: string;
  allowedActions: TodoAction[];
};
export type TodoHistory = {
  id: string;
  actor: TodoActor;
  action: string;
  summary: string;
  reason: string;
  createdAt: string
};

export const todoStatusNames: Record<TodoStatus, string> = {
  pending: "待处理",
  in_progress: "进行中",
  completed: "已完成",
  cancelled: "已取消"
};
export const todoSourceNames: Record<TodoSource["sourceType"], string> = {
  manual: "手动创建",
  message: "对话消息",
  workflow: "工作流结果"
};
