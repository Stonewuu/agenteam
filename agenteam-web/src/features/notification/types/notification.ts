import type {ApiPage} from "@/lib/http/use-api-query";

export type Notification = {
  id: string;
  sequence: string;
  category: string;
  title: string;
  body: string;
  targetType: string | null;
  targetId: string | null;
  readAt: string | null;
  createdAt: string
};
export type NotificationPage = ApiPage<Notification> & { throughSequence: string };
export type UnreadCount = { count: number; throughSequence: string };
