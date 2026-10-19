"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {CollaborationPage} from "@/features/collaboration/components/collaboration-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <CollaborationPage enterpriseId={enterpriseId}/>;
}
