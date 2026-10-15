"use client";

import {DetailStatus} from "@/components/ui/detail-section";
import {IconAlertCircle, IconArrowsJoin, IconCircleCheck, IconClock, IconPlayerStop} from "@/components/ui/icons";
import {deliveryStatus} from "@/features/chat/types/chat";
import {useT} from "@/lib/i18n/locale-provider";
import {collaborationStatus} from "../types/collaboration";

export function CollaborationStatus({status, delivery = false}: {status: string; delivery?: boolean}) {
  const t = useT();
  const success = delivery ? status === "sent" : status === "completed";
  const failure = ["failed", "dead"].includes(status);
  const attention = ["waiting_input", "waiting_approval", "blocked", "unknown", "retry_wait"].includes(status);
  const active = ["active", "running", "sending", "handed_off"].includes(status);
  const Icon = success ? IconCircleCheck : failure || attention ? IconAlertCircle
    : status === "handed_off" ? IconArrowsJoin : ["cancelled", "cancelling", "superseded"].includes(status) ? IconPlayerStop : IconClock;
  return <DetailStatus tone={success ? "success" : failure ? "danger" : attention ? "warning" : active ? "accent" : "neutral"}
    icon={<Icon size={14}/>}>
    {t((delivery ? deliveryStatus : collaborationStatus)[status] ?? "暂时无法读取状态")}
  </DetailStatus>;
}
