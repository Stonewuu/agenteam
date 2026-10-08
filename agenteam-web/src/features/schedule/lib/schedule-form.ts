import type {Employee} from "@/features/employee/types/employee";
import type {Schedule, ScheduleActionInput, ScheduleRecipient, ScheduleRule, ScheduleWrite} from "../types/schedule";

export type ScheduleForm = ScheduleRule & {
  name: string; enabled: boolean; maxRetries: number; actionType: ScheduleActionInput["type"];
  hireId: string; agentVersionId: string | null; inputText: string;
  title: string; body: string; recipients: ScheduleRecipient[];
};

export function initialScheduleForm(initial: Schedule | null, timezone: string, employee?: Employee): ScheduleForm {
  return {
    name: initial?.name ?? "", enabled: initial?.enabled ?? true, maxRetries: initial?.maxRetries ?? 0,
    frequency: initial?.frequency ?? "daily", localDate: initial?.localDate ?? null, localTime: initial?.localTime.slice(0, 5) ?? "09:00",
    weekdays: initial?.weekdays ?? [], monthDay: initial?.monthDay ?? null, timezone: initial?.timezone ?? timezone,
    actionType: initial?.action.type === "notification.send" ? "notification.send" : "agent.run",
    hireId: initial?.hireId ?? employee?.hireId ?? "", agentVersionId: initial?.agentVersionId ?? null,
    inputText: initial?.inputText ?? "", title: typeof initial?.action.config.title === "string" ? initial.action.config.title : "",
    body: typeof initial?.action.config.body === "string" ? initial.action.config.body : "", recipients: initial?.action.recipients ?? [],
  };
}

export function scheduleFormRequest(form: ScheduleForm): ScheduleWrite {
  const {name, enabled, frequency, localDate, localTime, weekdays, monthDay, timezone} = form;
  const action: ScheduleActionInput = form.actionType === "agent.run" ? {type: "agent.run", schemaVersion: 1,
    config: {hireId: form.hireId, agentVersionId: form.agentVersionId, inputText: form.inputText.trim()}}
    : {type: "notification.send", schemaVersion: 1, config: {title: form.title.trim(), body: form.body.trim(),
      recipients: form.recipients.map(person => ({userId: person.userId, connectionIds: person.channels.map(channel => channel.connectionId)}))}};
  return {name: name.trim(), enabled, frequency, localDate, localTime, weekdays, monthDay, timezone: timezone.trim(),
    maxRetries: form.actionType === "agent.run" ? form.maxRetries : 0, action};
}
