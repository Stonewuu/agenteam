import type {ApiPage} from "@/lib/http/use-api-query";
import type {UnreadCount} from "@/features/notification/types/notification";

export type AnnouncementLevel = {
  code: string;
  name: string;
  priority: number;
  tone: "danger" | "info" | "neutral";
  popup: boolean
};
export type Announcement = {
  id: string; scope: "platform" | "enterprise"; enterpriseId: string | null; title: string; content: string;
  contentFormat?: "plain_text" | "rich_text";
  publisherName?: string | null;
  level: AnnouncementLevel; enabled: boolean; version: string; revision: string; publicationSequence: string | null;
  publishedAt: string | null; createdAt: string; updatedAt: string; readAt: string | null;
};
export type AnnouncementPage = ApiPage<Announcement> & { throughSequence: string };
export type AnnouncementManagementPage = ApiPage<Announcement> & { canManage: boolean; levels: AnnouncementLevel[] };
export type AnnouncementUnread = { count: number; throughSequence: string; nextPopup: Announcement | null };
export type NotificationCenter = { notifications: UnreadCount; announcements: AnnouncementUnread };

export function announcementTitle(value: Announcement, t: (message: string) => string = (message) => message): string {
  return `${t(value.scope === "platform" ? "平台公告" : "企业公告")} - ${value.title}`;
}
