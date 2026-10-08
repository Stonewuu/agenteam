"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {useApiQuery} from "@/lib/http/use-api-query";
import {QueryState} from "@/components/ui/query-state";
import {Toggle} from "@/components/ui/toggle";
import {IconPlug} from "@/components/ui/icons";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {PluginConfig, ResourceDetail} from "@/features/resource/types/resource";
import type {ConnectionCheck, PluginTool} from "../types/plugin";
import {toolDisplayName, toolOperationLabel} from "../lib/tool-display-name";
import {changeTool, remoteSource} from "../lib/plugin-collection";
import ui from "@/components/ui/surface.module.css";
import styles from "./plugin.module.css";

export function PluginToolsPanel({enterpriseId, detail, value, onChange, dirty, onChecked, onChecking, readOnly}: {
  enterpriseId: string;
  detail: ResourceDetail;
  value: PluginConfig;
  onChange: (value: PluginConfig) => void;
  dirty: boolean;
  onChecked: () => Promise<void>;
  onChecking: (value: boolean) => void;
  readOnly: boolean;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [refresh, setRefresh] = useState(0);
  const [latest, setLatest] = useState<ConnectionCheck | null>(null);
  const saved = detail.draft as PluginConfig;
  const source = remoteSource(value);
  const changed = JSON.stringify(remoteSource(saved)) !== JSON.stringify(source) || saved.timeoutSeconds !== value.timeoutSeconds;
  const check = latest ?? detail.connectionCheck;
  const tools = useApiQuery<PluginTool[]>(changed ? null : organizationPath(enterpriseId, `/plugins/${encodeURIComponent(detail.resource.id)}/tools`), refresh + Number(detail.resource.revision));
  const enabled = detail.resource.allowedActions.includes("test") && !readOnly;
  const displayName = (tool: { name: string; displayName?: string }) => toolDisplayName(tool, undefined, uiText);
  const checkConnection = () => void action.execute(async () => {
    onChecking(true);
    try {
      const result = await action.mutation.run<ConnectionCheck>(organizationPath(enterpriseId, `/plugins/${encodeURIComponent(detail.resource.id)}/check`),
        {method: "POST", revision: detail.resource.revision, timeoutMs: 130000});
      setLatest(result);
      setRefresh((value) => value + 1);
      await onChecked();
    } finally {
      onChecking(false);
    }
  }, "");
  return <section className={styles.section} aria-label={uiText("插件工具")}>
    <div className={styles.heading}><h3>{uiText("工具")}</h3>{enabled &&
      <Button type="button" className={ui.button} disabled={dirty || action.busy}
              onClick={checkConnection}>{action.busy ? uiText("正在检查…") : check?.success ? uiText("刷新工具") : uiText("检查连接")}</Button>}
    </div>
    {dirty && enabled && <p className={ui.description}>{uiText("保存草稿后可检查连接。")}</p>}
    {check && !changed &&
      <div className={ui.feedback}><p className={check.success ? ui.notice : ui.error}>{check.summary}</p><span
        className={ui.description}>{uiText("上次检查：")}<EnterpriseDateTime value={check.checkedAt}/></span>
        {check.toolChanges.some((tool) => tool.change !== "unchanged") &&
          <div className={ui.chips}>{check.toolChanges.filter((tool) => tool.change !== "unchanged").map((tool) =>
            <span className={ui.chip}
                  key={tool.name}>{displayName(tools.data?.find((item) => item.name === tool.name) ?? tool)} · {{
              added: uiText("新增"),
              removed: uiText("已移除"),
              changed: uiText("已更新"),
              unchanged: uiText("未变化")
            }[tool.change]}</span>)}</div>}</div>}
    <MutationFeedback action={action}/>
    {changed ? <p className={ui.description}>{uiText("保存连接信息后读取工具。")}</p> :
      <QueryState {...tools} hasData={Boolean(tools.data?.length)}
                  empty={check ? uiText("当前没有可选工具。") : uiText("检查连接后选择需要的工具。")}>
        <div className={styles.tools}>{tools.data?.map((tool) => <article className={styles.tool} key={tool.name}>
          <div className={styles.toolHeading}><IconPlug size={18}/><strong>{displayName(tool)}</strong><span
            className={`badge ${tool.operationClass === "read" ? "blue" : "amber"}`}>{uiText(toolOperationLabel(tool.operationClass))}</span>
          </div>
          {tool.description && <p className={ui.description}>{tool.description}</p>}
          <footer className={styles.toolToggle}><span>{uiText("允许调用")}</span><Toggle
            label={uiText("允许调用{0}", [displayName(tool)])} hideLabel
            checked={value.tools.some((item) => item.sourceId === source?.id && item.name === tool.name)}
            disabled={readOnly || action.busy} onChange={(checked) => {
            if (source) {
              onChange(changeTool(value, source.id, tool.name, checked));
            }
          }}/></footer>
        </article>)}</div>
      </QueryState>}
  </section>;
}
