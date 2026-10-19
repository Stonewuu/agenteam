"use client";

import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {useParams} from "next/navigation";
import {Suspense} from "react";
import {OrganizationAdmin} from "@/features/enterprise/components/organization-admin";
import {WorkspacePageLoading} from "@/features/workspace/components/workspace-loading";

export default function Page() {
  const {enterpriseId} = useWorkspaceLocation();
  const {section} = useParams<{section?: string[]}>();
  return <Suspense fallback={<WorkspacePageLoading embedded/>}>
    <OrganizationAdmin enterpriseId={enterpriseId}
                       section={section?.length === 1 ? section[0] : section?.length ? "unavailable" : "default"}/>
  </Suspense>;
}
