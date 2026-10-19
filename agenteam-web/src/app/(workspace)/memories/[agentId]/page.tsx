"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams} from "next/navigation";
import {MemoryDetailPage} from "@/features/memory/components/memory-detail-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {agentId} = useParams<{agentId: string}>();
  return <MemoryDetailPage enterpriseId={enterpriseId} agentId={agentId}/>;
}
