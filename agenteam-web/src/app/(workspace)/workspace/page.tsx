"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {HomePage} from "@/features/workspace/components/home-page";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  return <HomePage enterpriseId={enterpriseId}/>;
}
