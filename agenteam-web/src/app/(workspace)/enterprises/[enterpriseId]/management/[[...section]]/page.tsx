import {Suspense} from "react";
import {OrganizationAdmin} from "@/features/enterprise/components/organization-admin";
import {WorkspacePageLoading} from "@/features/workspace/components/workspace-loading";

export default async function Page({params}: { params: Promise<{ enterpriseId: string; section?: string[] }> }) {
  const {enterpriseId, section} = await params;
  return <Suspense fallback={<WorkspacePageLoading embedded/>}>
    <OrganizationAdmin enterpriseId={enterpriseId}
                       section={section?.length === 1 ? section[0] : section?.length ? "unavailable" : "default"}/>
  </Suspense>;
}
