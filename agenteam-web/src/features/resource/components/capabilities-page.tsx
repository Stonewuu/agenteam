"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {EnterpriseGate} from "@/features/auth/components/enterprise-gate";
import {PlatformShell} from "@/features/workspace/components/platform-shell";
import {ResourceEditor} from "./resource-editor";
import {ResourceList} from "./resource-list";
import {resourceTypes} from "../lib/resource-display";
import {WorkflowDevelopmentPage, workflowInDevelopment} from "@/features/workflow/components/workflow-development";
import ui from "@/components/ui/surface.module.css";

export function CapabilitiesPage({enterpriseId, section, resourceId}: {
  enterpriseId: string;
  section?: string;
  resourceId?: string
}) {
  const uiText = useT();
  return <EnterpriseGate key={enterpriseId} enterpriseId={enterpriseId} permission="capabilities.view">{({
                                                                                                           user,
                                                                                                           context
                                                                                                         }) => {
    const type = section ? localizeCatalog(resourceTypes, uiText).find((type) => type.path === section) : localizeCatalog(resourceTypes, uiText).find((type) => context.permissions.some((code) => [`${type.kind}.view`, `${type.kind}.create`].includes(code)));
    return <PlatformShell user={user} context={context} area="capabilities" title={type?.label ?? uiText("能力中心")}>
      {type?.kind === "workflow" && workflowInDevelopment ? <WorkflowDevelopmentPage/> : type ? resourceId ?
          <ResourceEditor key={`${type.kind}:${resourceId}`} enterpriseId={enterpriseId} kind={type.kind} id={resourceId}
                          permissions={context.permissions} permissionVersion={context.permissionVersion}/>
          : <ResourceList key={`${type.kind}:${context.permissionVersion}`} enterpriseId={enterpriseId} kind={type.kind}
                          permissions={context.permissions}/>
        : <div className={ui.empty}>{uiText("暂无可访问的能力中心内容。")}</div>}
    </PlatformShell>;
  }}</EnterpriseGate>;
}
