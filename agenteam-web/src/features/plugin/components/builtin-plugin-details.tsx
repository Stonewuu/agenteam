"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import Link from "next/link";
import {useRouter} from "next/navigation";
import {Button} from "@/components/ui/button";
import {Dialog} from "@/components/ui/dialog";
import {PageHeader} from "@/components/ui/page-header";
import {QueryState} from "@/components/ui/query-state";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconArrowLeft, IconCopy, IconHistory} from "@/components/ui/icons";
import {ResourceActions} from "@/features/resource/components/resource-actions";
import {ResourceVersions} from "@/features/resource/components/resource-versions";
import {resourcePage, resourceStatus} from "@/features/resource/lib/resource-display";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {ResourceDetail} from "@/features/resource/types/resource";
import type {PluginTool} from "../types/plugin";
import {toolDisplayName, toolOperationLabel} from "../lib/tool-display-name";
import styles from "./tool-collection.module.css";
import ui from "@/components/ui/surface.module.css";

export function BuiltinPluginDetails({enterpriseId, detail, onChanged}: {
  enterpriseId: string;
  detail: ResourceDetail;
  onChanged: () => void
}) {
  const uiText = useT();
  const {resource} = detail;
  const [history, setHistory] = useState(false);
  const router = useRouter();
  const action = useFormAction();
  const tools = useApiQuery<PluginTool[]>(organizationPath(enterpriseId, `/plugins/${encodeURIComponent(resource.id)}/tools`), resource.revision);
  const copy = () => void action.execute(async () => {
    const result = await action.mutation.run<ResourceDetail>(organizationPath(enterpriseId, `/resources/${encodeURIComponent(resource.id)}/copy`),
      {method: "POST", body: {name: uiText("{0} 自定义", [resource.name]).slice(0, 80)}});
    router.push(`${resourcePage(enterpriseId, "plugin", result.resource.id)}?section=connection`);
  }, uiText("已创建插件草稿。"));
  return <div className={styles.preset}>
    <PageHeader title={uiText(resource.name)} size="small"
                leading={<><Link className="icon-button" href={resourcePage(enterpriseId, "plugin")}
                                 aria-label={uiText("返回插件列表")}><IconArrowLeft size={20}/></Link><ResourceAvatar
                  icon={resource.icon} color={resource.color}/></>}
                metadata={<span className="badge neutral">{uiText(resourceStatus(resource))}</span>} actions={<>
      <Button className={ui.button} type="button" onClick={() => setHistory(true)}><IconHistory
        size={16}/>{uiText("历史")}</Button>
      {resource.allowedActions.includes("copy") &&
        <Button type="button" className={ui.primary} onClick={copy} disabled={action.busy}><IconCopy
          size={16}/>{uiText("基于此创建插件")}</Button>}
      <ResourceActions enterpriseId={enterpriseId} resource={resource} dirty={action.busy} onChanged={onChanged}/>
    </>}/>
    <p className={styles.presetIntro}>{uiText(resource.description)}</p>
    <div className={styles.presetTools}>
      <div className={styles.heading}><h3>{uiText("工具")}{tools.data && <small>{tools.data.length}</small>}</h3></div>
      <QueryState {...tools} hasData={Boolean(tools.data?.length)}
                  empty={uiText("当前没有可用工具。")}>{tools.data?.map((tool) => <div key={tool.id ?? tool.name}
                                                                                      className={styles.selectedTool}>
        <span><strong>{toolDisplayName(tool, resource.name, uiText)}</strong><span>{uiText(tool.description)}</span></span><span
        className={`badge ${tool.operationClass === "read" ? "blue" : "amber"}`}>{uiText(toolOperationLabel(tool.operationClass))}</span>
      </div>)}</QueryState>
    </div>
    <MutationFeedback action={action}/>
    {history && <Dialog title={uiText("发布历史")} drawer onClose={() => setHistory(false)}><ResourceVersions
      enterpriseId={enterpriseId} resource={resource} permissions={[]} onLoaded={() => {
      setHistory(false);
      onChanged();
    }} onChanged={onChanged}/></Dialog>}
  </div>;
}
