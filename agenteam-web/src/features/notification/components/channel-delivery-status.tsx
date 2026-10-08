"use client";

import {DetailStatus} from "@/components/ui/detail-section";
import {IconAlertCircle, IconCheck, IconClock, IconPlayerStop} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {deliveryStatuses, type ChannelDelivery} from "../types/channel-delivery";

export function ChannelDeliveryStatus({status}: {status: ChannelDelivery["status"]}) {
  const t = useT();
  const failed = status === "failed" || status === "blocked";
  const uncertain = status === "unknown" || status === "expired" || status === "retry_wait";
  const Icon = status === "accepted" ? IconCheck : failed || uncertain ? IconAlertCircle : status === "cancelled" ? IconPlayerStop : IconClock;
  return <DetailStatus tone={status === "accepted" ? "success" : failed ? "danger" : uncertain ? "warning" : "neutral"}
    icon={<Icon size={14}/>}>{t(deliveryStatuses[status])}</DetailStatus>;
}
