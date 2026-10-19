import {Suspense, type ReactNode} from "react";
import {WorkspaceLayout} from "@/features/workspace/components/workspace-layout";
import {WorkspaceLocationProvider} from "@/features/auth/components/workspace-location-provider";
import {WorkspaceLoading} from "@/features/workspace/components/workspace-loading";

export default function Layout({children}: { children: ReactNode }) {
  return <Suspense fallback={<WorkspaceLoading/>}><WorkspaceLocationProvider>
    <WorkspaceLayout>{children}</WorkspaceLayout>
  </WorkspaceLocationProvider></Suspense>;
}
