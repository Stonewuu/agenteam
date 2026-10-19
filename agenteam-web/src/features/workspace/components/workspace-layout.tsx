"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {type ReactNode} from "react";
import {usePathname} from "next/navigation";
import {useWorkspaceLocation} from "@/features/auth/components/workspace-location-provider";
import {EnterpriseIdentityProvider} from "@/features/auth/components/enterprise-gate";
import {EnterpriseLayout} from "./enterprise-layout";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import {platformManagementNavigation} from "../lib/navigation";

/** 企业、个人设置和平台管理共用布局，常规导航不会重新创建侧栏。 */
export function WorkspaceLayout({children}: { children: ReactNode }) {
  const uiText = useT();
  const {user, enterpriseId, context} = useWorkspaceLocation();
  const pathname = usePathname();
  const platformPage = localizeCatalog(platformManagementNavigation, uiText).find((item) => item.path === pathname);
  if (!context) {
    return platformPage ? <SystemManagementGate title={platformPage.label}>{() => children}</SystemManagementGate>
      : <main className="page-content">{children}</main>;
  }
  return <EnterpriseIdentityProvider key={`${user.id}:${user.identityRevision}:${enterpriseId}`} user={user} context={context}>
    <EnterpriseLayout enterpriseId={enterpriseId}>{children}</EnterpriseLayout>
  </EnterpriseIdentityProvider>;
}
