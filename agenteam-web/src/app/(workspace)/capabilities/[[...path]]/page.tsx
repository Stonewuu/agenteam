"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams} from "next/navigation";
import {notFound} from "next/navigation";
import {CapabilitiesPage} from "@/features/resource/components/capabilities-page";
import {resourceTypes} from "@/features/resource/lib/resource-display";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {path} = useParams<{path?: string[]}>();
  if (path && (path.length > 2 || !resourceTypes.some((type) => type.path === path[0]))) {
    notFound();
  }
  return <CapabilitiesPage enterpriseId={enterpriseId} section={path?.[0]} resourceId={path?.[1]}/>;
}
