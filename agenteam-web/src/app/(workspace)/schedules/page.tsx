"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useSearchParams} from "next/navigation";
import {SchedulePage} from "@/features/schedule/components/schedule-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const agent = useSearchParams().get("agent") ?? undefined;
  return <SchedulePage enterpriseId={enterpriseId} initialAgent={typeof agent === "string" ? agent : undefined}/>;
}
