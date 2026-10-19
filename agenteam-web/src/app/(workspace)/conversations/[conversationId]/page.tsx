"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {EnterpriseWorkspace} from "@/features/auth/components/enterprise-workspace";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <EnterpriseWorkspace enterpriseId={enterpriseId}/>;
}
