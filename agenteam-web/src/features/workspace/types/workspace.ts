import type {Conversation} from "@/features/agent/types/execution";
import type {Employee} from "@/features/employee/types/employee";
import type {Todo} from "@/features/todo/types/todo";

export type HomeSummary = {
  recentConversations: Conversation[];
  todos: Todo[];
  employees: Employee[];
  openTodoCount: number;
  enabledScheduleCount: number
};
export type SearchItem = {
  id: string;
  name: string;
  description: string;
  targetType: "menu" | "conversation" | "employee" | "resource";
  targetId: string;
  resourceKind: "agent" | "skill" | "plugin" | "workflow" | "knowledge" | "data" | null;
  icon: string | null;
  color: string | null
};
export type SearchResult = { groups: { key: string; label: string; items: SearchItem[] }[] };
