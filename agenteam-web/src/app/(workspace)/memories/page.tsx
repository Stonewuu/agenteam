"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {MemoryPage} from "@/features/memory/components/memory-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <MemoryPage enterpriseId={enterpriseId}/>;
}
