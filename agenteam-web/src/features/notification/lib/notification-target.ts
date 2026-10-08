import {enterprisePath} from "@/features/auth/lib/identity-navigation";
import type {Notification} from "../types/notification";

export function notificationTarget(enterprise: string, value: Notification, permissions: string[]): string | null {
  if (!value.targetId) {
    return null;
  }
  const from = `?notification=${encodeURIComponent(value.id)}`;
  if (value.targetType === "conversation" && permissions.includes("conversation.view")) {
    return enterprisePath(enterprise, `/conversations/${encodeURIComponent(value.targetId)}${from}`);
  }
  if (value.targetType === "schedule" && permissions.includes("schedule.view")) {
    return enterprisePath(enterprise, `/schedules/${encodeURIComponent(value.targetId)}${from}`);
  }
  if (value.targetType === "todo" && permissions.includes("todo.view")) {
    return enterprisePath(enterprise, `/todos/${encodeURIComponent(value.targetId)}${from}`);
  }
  if (value.targetType === "hire_request" && permissions.some((permission) => ["agent.hire", "agent.hire_approve"].includes(permission))) {
    return enterprisePath(enterprise, `/employees?tab=applications&application=${encodeURIComponent(value.targetId)}&notification=${encodeURIComponent(value.id)}`);
  }
  return null;
}
