"use client";

import {EmployeeIdentity} from "@/features/employee/components/employee-identity";
import {useT} from "@/lib/i18n/locale-provider";
import type {Schedule} from "../types/schedule";

export function ScheduleActionSummary({plan}: {plan: Schedule}) {
  const t = useT();
  return plan.action.type === "agent.run" && plan.agentName ? <EmployeeIdentity name={plan.agentName} icon={plan.agentIcon} color={plan.agentColor}/>
    : <span>{t(plan.action.name)}{plan.action.recipients.length ? t(" · {0} 人", [plan.action.recipients.length]) : ""}</span>;
}

export function scheduleSummaryText(plan: Schedule) {
  return plan.action.type === "notification.send" ? String(plan.action.config.title ?? "") : plan.inputText ?? "";
}
