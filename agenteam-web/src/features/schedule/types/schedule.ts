import type {Versioned} from "@/features/enterprise/types/organization";
import type {ChannelDelivery} from "@/features/notification/types/channel-delivery";

export type ScheduleRule = {
  frequency: "once" | "daily" | "weekly" | "monthly";
  localDate: string | null;
  localTime: string;
  weekdays: number[];
  monthDay: number | null;
  timezone: string
};
export type ScheduleWrite = ScheduleRule & {
  name: string;
  action: ScheduleActionInput;
  enabled: boolean;
  maxRetries: number
};
export type ScheduleActionInput = {
  type: "agent.run"; schemaVersion: number; config: {hireId: string; agentVersionId: string | null; inputText: string};
} | {
  type: "notification.send"; schemaVersion: number;
  config: {title: string; body: string; recipients: {userId: string; connectionIds: string[]}[]};
};
export type ScheduleChannel = {connectionId: string; name: string; providerCode: string; providerName?: string};
export type ScheduleRecipient = {userId: string; name: string; channels: ScheduleChannel[]};
export type ScheduleActionOption = {type: string; name: string; schemaVersions: number[]; usesAgent: boolean; configSchema: Record<string, unknown>};
export type ScheduleAction = {type: string; name: string; schemaVersion: number; config: Record<string, unknown>; recipients: ScheduleRecipient[]};
export type Occurrence = {
  id: string;
  scheduleId: string;
  triggerKind: "scheduled" | "manual";
  scheduledFor: string;
  runId: string | null;
  conversationId: string | null;
  status: "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled" | "skipped" | "missed" | "blocked" | "partially_failed" | "unknown";
  reasonCode: string | null;
  reason: string | null;
  attemptCount: number;
  startedAt: string | null;
  finishedAt: string | null;
  actionType: string;
  actionSchemaVersion: number;
  scheduleRevision: string | null;
  snapshotOrigin: "captured" | "legacy_unavailable";
  actionSnapshot: Record<string, unknown> | null;
  actionResult: Record<string, unknown>;
};
export type OccurrenceDetail = {occurrence: Occurrence; recipients: {
  userId: string; name: string; inAppStatus: string; reason: string | null; channels: ChannelDelivery[];
}[]};
export type Schedule = Versioned & Omit<ScheduleWrite, "action"> & {
  action: ScheduleAction;
  hireId: string | null;
  inputText: string | null;
  agentVersionId: string | null;
  agentId: string | null;
  agentName: string | null;
  agentVersionNo: number | null;
  agentIcon: string | null;
  agentColor: string | null;
  nextRunAt: string | null;
  pauseReason: string | null;
  activeOccurrenceId: string | null;
  activeOccurrence: Occurrence | null;
  latestOccurrence: Occurrence | null
};
export type ScheduleTimes = { timezone: string; times: string[]; warnings: string[] };
