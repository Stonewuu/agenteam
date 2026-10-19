"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams} from "next/navigation";
import {ScheduleDetailPage} from "@/features/schedule/components/schedule-detail-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {scheduleId} = useParams<{scheduleId: string}>();
  return <ScheduleDetailPage enterpriseId={enterpriseId} scheduleId={scheduleId}/>;
}
