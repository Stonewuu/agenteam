"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import {VersionPicker} from "@/features/resource/components/version-picker";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {DependencyOptions, WorkflowNode} from "../types/workflow";
import {toolOperationLabel} from "@/features/plugin/lib/tool-display-name";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

export function WorkflowCapabilityFields({enterprise, node, permissions, onChange, readOnly}: {
  enterprise: string;
  node: WorkflowNode;
  permissions: string[];
  onChange: (value: Record<string, unknown>) => void;
  readOnly: boolean;
}) {
  const uiText = useT();
  const plugin = node.type === "tool", kind = plugin ? "plugin" : "agent",
    key = plugin ? "pluginVersionId" : "agentVersionId";
  const selected = typeof node.config[key] === "string" ? node.config[key] as string : null;
  const allowed = permissions.includes(plugin ? "plugin.invoke" : "agent.run");
  const canConfigure = ["workflow.create", "workflow.edit", "workflow.preview"].some((permission) => permissions.includes(permission));
  const options = useApiQuery<DependencyOptions>(selected && allowed && canConfigure ? organizationPath(enterprise, `/workflows/dependency-options/${encodeURIComponent(selected)}`) : null);
  const tools = options.data?.tools ?? [];
  const selectedTool = node.config.toolId ? tools.find((tool) => (tool.entryId ?? tool.id) === node.config.toolId)
    : tools.filter((tool) => tool.name === node.config.toolName).length === 1 ? tools.find((tool) => tool.name === node.config.toolName) : undefined;
  const selectedKey = String(node.config.toolId ?? selectedTool?.entryId ?? selectedTool?.id ?? "");
  const changeTool = (changes: Record<string, unknown>) => {
    const config = {...node.config, ...changes};
    delete config.toolName;
    onChange(config);
  };
  return <div className={styles.fields}>
    <VersionPicker enterpriseId={enterprise} kind={kind} title={plugin ? uiText("插件版本") : uiText("执行智能体版本")}
                   selected={selected ? [selected] : []} maximum={1} allowed={allowed} readOnly={readOnly}
                   onChange={(ids) => plugin ? changeTool({
                     [key]: ids[0] ?? null,
                     toolId: null
                   }) : onChange({
                     ...node.config,
                     [key]: ids[0] ?? null, ...(node.type === "skill" ? {skillVersionId: null} : {})
                   })}/>
    {options.loading && <p className={ui.loading}>{uiText("正在读取可选能力…")}</p>}
    {options.error &&
      <p className={ui.error} role="alert">{localizeUiMessage(options.error ?? "", uiText)}<Button type="button"
                                                                                                   className={ui.button}
                                                                                                   onClick={options.retry}>{uiText("重新读取")}</Button>
      </p>}
    {options.data?.agentType === "workflow" && <p className={ui.error}>{uiText("请选择对话型或任务型智能体。")}</p>}
    {node.type === "skill" && <label className={ui.field}><span>{uiText("执行技能")}</span><Select className={ui.select}
                                                                                                   value={String(node.config.skillVersionId ?? "")}
                                                                                                   onChange={(event) => onChange({
                                                                                                     ...node.config,
                                                                                                     skillVersionId: event.target.value || null
                                                                                                   })}>
      <option value="">{uiText("请选择技能")}</option>
      {node.config.skillVersionId && !options.data?.skills.some((skill) => skill.versionId === node.config.skillVersionId) ?
        <option value={String(node.config.skillVersionId)} disabled>{uiText("原技能当前不可选")}</option> : null}
      {options.data?.skills.map((skill) => <option key={skill.versionId}
                                                   value={skill.versionId}>{skill.name}{uiText(" · 版本 ")}{skill.versionNo}</option>)}
    </Select></label>}
    {plugin && <><label className={ui.field}><span>{uiText("执行工具")}</span><Select className={ui.select}
                                                                                      value={selectedKey}
                                                                                      onChange={(event) => changeTool({toolId: event.target.value || null})}>
      <option value="">{uiText("请选择工具")}</option>
      {selectedKey && !selectedTool ? <option value={selectedKey} disabled>{uiText("原工具当前不可选")}</option> : null}
      {tools.map((tool) => <option key={tool.entryId ?? tool.id}
                                   value={tool.entryId ?? tool.id ?? ""}>{tool.displayName || tool.name}{tool.sourceName ? ` · ${tool.sourceName}` : ""}</option>)}
    </Select></label>{selectedTool && <div className={ui.feedback}>
      <p className={ui.description}>{selectedTool.description}</p><p
      className={ui.notice}>{uiText("操作类型：")}{uiText(toolOperationLabel(selectedTool.operationClass))}</p>
      {Object.keys(selectedTool.inputSchema.properties as object ?? {}).length > 0 && <div className={styles.fields}>
        <strong>{uiText("工具参数")}</strong>{Object.entries((selectedTool.inputSchema.properties ?? {}) as Record<string, {
        description?: string;
        type?: string
      }>).map(([name, field]) =>
        <p className={ui.description}
           key={name}>{name}{Array.isArray(selectedTool.inputSchema.required) && selectedTool.inputSchema.required.includes(name) ? uiText("（必填）") : ""}{field.description ? `：${field.description}` : ""}</p>)}
      </div>}
    </div>}</>}
  </div>;
}
