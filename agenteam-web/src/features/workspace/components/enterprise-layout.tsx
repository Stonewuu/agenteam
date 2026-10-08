"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {usePathname} from "next/navigation";
import type {ReactNode} from "react";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {SystemManagementGate} from "@/features/auth/components/system-management-gate";
import {PlatformShell} from "./platform-shell";
import {
  capabilityNavigation,
  managementNavigation,
  platformManagementNavigation,
  workspaceNavigation
} from "../lib/navigation";

/** 企业布局保持挂载，切换页面只更新内容和当前导航。 */
export function EnterpriseLayout({enterpriseId, children}: { enterpriseId: string; children: ReactNode }) {
  const uiText = useT();
  const pathname = usePathname();
  const platformPage = localizeCatalog(platformManagementNavigation, uiText).find((item) => item.path === pathname);
  const [, , , section, detail] = pathname.split("/");
  const area = platformPage || section === "management" ? "management" : section === "capabilities" ? "capabilities" : "user";
  const fullHeight = section === "new-task" || section === "conversations";
  return <EnterpriseGate enterpriseId={enterpriseId} onUnavailable={platformPage ? () =>
    <SystemManagementGate title={platformPage.label}>{() => children}</SystemManagementGate> : undefined}>{({
                                                                                                              user,
                                                                                                              context
                                                                                                            }) => {
    const title = platformPage?.label ?? (pathname === "/settings" ? uiText("个人设置") : area === "management" ? localizeCatalog(managementNavigation, uiText).find((item) => item.id === detail)?.label ?? uiText("企业概览")
      : area === "capabilities" ? localizeCatalog(capabilityNavigation, uiText).find((item) => item.path === detail)?.label ?? uiText("能力中心")
        : localizeCatalog(workspaceNavigation, uiText).find((item) => item.path === section)?.label ?? ({
        "new-task": uiText("新对话"),
        notifications: uiText("通知与公告"),
        settings: detail === "channels" ? uiText("企业账号绑定") : uiText("个人设置"),
        memories: uiText("个人偏好记忆")
      }[section] ?? uiText("工作台")));
    return <PlatformShell user={user} context={context} area={area} title={title}
                          fullHeight={fullHeight}>{children}</PlatformShell>;
  }}</EnterpriseGate>;
}
