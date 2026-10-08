import type {Translator} from "@/lib/i18n/translate";
import {localizeUiMessage} from "@/lib/i18n/ui-message";
import type {Notification} from "../types/notification";

type NotificationContent = Pick<Notification, "category" | "targetType" | "title" | "body">;

/** 依据服务端内置通知来源识别系统文案；定时通知的自定义正文没有目标类型，保持原样。 */
export function notificationContent(value: NotificationContent, t: Translator): Pick<Notification, "title" | "body"> {
  const builtin = ((value.category === "execution" || value.category === "approval" || value.category === "permission") && value.targetType === "conversation")
    || (value.category === "hire" && value.targetType === "hire_request")
    || (value.category === "todo" && value.targetType === "todo")
    || (value.category === "schedule" && value.targetType === "schedule")
    || (value.category === "integration" && (value.targetType === null || value.targetType === "notification_delivery"));
  return {
    title: builtin ? localizeUiMessage(value.title, t) : value.title,
    body: builtin ? localizeUiMessage(value.body, t) : value.body,
  };
}
