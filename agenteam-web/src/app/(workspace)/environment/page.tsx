"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {EnvironmentPage} from "@/features/environment/components/environment-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <EnvironmentPage enterpriseId={enterpriseId}/>;
}
