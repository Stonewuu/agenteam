import {SettingsPage} from "@/features/auth/components/settings-page";
import {Suspense} from "react";
import {WorkspacePageLoading} from "@/features/workspace/components/workspace-loading";

export default function Page() {
  return <Suspense fallback={<WorkspacePageLoading layout="settings" embedded/>}><SettingsPage/></Suspense>;
}
