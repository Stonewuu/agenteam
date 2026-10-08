"use client";

import {useT} from "@/lib/i18n/locale-provider";



import {usePathname, useSearchParams} from "next/navigation";
import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import type {EnterpriseContext, IdentityUser} from "@/features/auth/types/identity";
import {organizationPath} from "../api/organization-api";
import {RolePanel} from "./role-panel";
import {MembersOverview} from "./members-overview";
import {TeamPanel} from "./team-panel";
import {EnterpriseProfilePanel} from "./enterprise-profile-panel";
import {CredentialPanel} from "@/features/plugin/components/credential-panel";
import {IntegrationPanel} from "@/features/integration/components/integration-panel";
import {ModelManagement} from "@/features/modelprofile/components/model-management";
import {ToolLogPanel} from "@/features/plugin/components/tool-log-panel";
import {UsagePanel} from "@/features/usage/components/usage-panel";
import styles from "./organization.module.css";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {canViewManagementSection, managementNavigation} from "@/features/workspace/lib/navigation";
import {AnnouncementManager} from "@/features/announcement/components/announcement-manager";

import {editionClientExtension} from "@/features/edition/client-extension";

const sections = managementNavigation;

export function OrganizationAdmin({enterpriseId, section}: { enterpriseId: string; section: string }) {
  return <EnterpriseGate key={enterpriseId} enterpriseId={enterpriseId} permission="admin.view">
    {({user, context}) => <AdminContent key={`${enterpriseId}:${section}`} user={user} context={context}
                                        section={section}/>}
  </EnterpriseGate>;
}

function AdminContent({user, context, section}: { user: IdentityUser; context: EnterpriseContext; section: string }) {
  const uiText = useT();
  const enterpriseId = context.enterprise.id;
  const pathname = usePathname();
  const params = useSearchParams();
  const query = params.get("query") ?? "";
  const allowed = sections.filter((item) => canViewManagementSection(item, context.permissions, context.capabilities));
  const active = allowed.find((item) => item.id === section) ?? (section === "default" ? allowed[0] : undefined);
  const onQuery = (value: string) => {
    const next = new URLSearchParams(params.toString());
    if (value) {
      next.set("query", value);
    } else {
      next.delete("query");
    }
    window.history.replaceState(null, "", pathname + (next.size ? `?${next}` : ""));
  };
  const common = {enterpriseId, permissions: context.permissions, query, onQuery};
  const extension = active ? editionClientExtension.managementPanels[active.id] : undefined;
  const ExtensionPanel = extension && context.capabilities.includes(extension.capability) ? extension.component : undefined;
  return <PlatformShell user={user} context={context} area="management" title={active?.label ?? uiText("企业管理")}>
    {(active?.id === "members" || active?.id === "invitations") && <MembersOverview {...common} userId={user.id}
                                                                                    initialTab={active.id === "invitations" ? "invitations" : "members"}/>}
    {active?.id === "teams" && <TeamPanel {...common} userId={user.id} displayName={context.member.displayName}/>}
    {active?.id === "roles" && !ExtensionPanel && <RolePanel {...common} />}
    {active?.id === "overview" && <EnterpriseProfilePanel enterpriseId={enterpriseId} context={context}
                                                          canManage={context.permissions.includes("enterprise.manage")}/>}
    {active?.id === "credentials" &&
      <CredentialPanel enterpriseId={enterpriseId} canManage={context.permissions.includes("credential.manage")}/>}
    {active?.id === "integrations" &&
      <IntegrationPanel enterpriseId={enterpriseId} canManage={context.permissions.includes("integration.manage")}
        canTest={context.permissions.includes("integration.test")} canViewDeliveries={context.permissions.includes("notification.delivery.view")}/>}
    {active?.id === "models" &&
      <ModelManagement enterpriseId={enterpriseId} canManage={context.permissions.includes("model.manage")}/>}
    {active?.id === "announcements" &&
      <AnnouncementManager endpoint={organizationPath(enterpriseId, "/announcements/manage")}/>}
    {active?.id === "tool-logs" &&
      <ToolLogPanel enterpriseId={enterpriseId} userId={user.id} permissions={context.permissions} query={query}
                    onQuery={onQuery}/>}
    {active?.id === "usage" && !ExtensionPanel && <UsagePanel enterpriseId={enterpriseId} permissions={context.permissions}/>}
    {ExtensionPanel && <ExtensionPanel user={user} context={context} query={query} onQuery={onQuery}/>}
    {!active && <div className={styles.empty}>{uiText("此页面暂时不可访问，请选择侧栏中的其他入口。")}</div>}
  </PlatformShell>;
}
