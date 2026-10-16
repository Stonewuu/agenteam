"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";
import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import {LoadingTransition} from "@/components/ui/loading-transition";
import {VersionPicker} from "@/features/resource/components/version-picker";
import type {DependencyOptions, WorkflowNode} from "../types/workflow";
import {parameterDefaults, parameterObject, selectedWorkflowTool, type VariableOption} from "../lib/workflow-parameters";
import {WorkflowSchemaFields} from "./workflow-schema-fields";
import ui from "@/components/ui/surface.module.css";
import form from "./workflow-parameters.module.css";

export function WorkflowCapabilityFields({enterprise, node, permissions, onChange, readOnly, options, loading, error, retry, variables}: {
  enterprise: string; node: WorkflowNode; permissions: string[]; onChange: (value: Record<string, unknown>) => void; readOnly: boolean;
  options?: DependencyOptions; loading: boolean; error?: string; retry: () => void; variables: VariableOption[];
}) {
  const text = useT(), plugin = node.type === "tool", kind = plugin ? "plugin" : "agent";
  const key = plugin ? "pluginVersionId" : "agentVersionId";
  const selected = typeof node.config[key] === "string" ? node.config[key] as string : null;
  const allowed = permissions.includes(plugin ? "plugin.invoke" : "agent.run");
  const tools = options?.tools ?? [], selectedTool = selectedWorkflowTool(tools, node.config);
  const selectedKey = String(node.config.toolId ?? selectedTool?.entryId ?? selectedTool?.id ?? "");
  const changeTool = (changes: Record<string, unknown>) => {
    const config = {...node.config, ...changes};
    delete config.toolName;
    onChange(config);
  };
  return <div className={form.stack}>
    <VersionPicker compact enterpriseId={enterprise} kind={kind} title={plugin ? text("插件") : text("执行智能体")}
      selected={selected ? [selected] : []} maximum={1} allowed={allowed} readOnly={readOnly}
      onChange={(ids) => plugin ? changeTool({[key]: ids[0] ?? null, toolId: null}) : onChange({
        ...node.config, [key]: ids[0] ?? null, ...(node.type === "skill" ? {skillVersionId: null} : {})
      })}/>
    <LoadingTransition state={loading ? "loading" : error ? "error" : "ready"} contentClassName={form.stack}>
      {loading && <p className={ui.loading}>{text("正在读取可选能力…")}</p>}
      {error && <div className={form.inline}><p className={ui.error} role="alert">{localizeUiMessage(error, text)}</p>
        <Button type="button" className={form.textAction} onClick={retry}>{text("重新读取")}</Button></div>}
      {options?.agentType === "workflow" && <p className={ui.error}>{text("请选择对话型或任务型智能体。")}</p>}
      {node.type === "skill" && selected && options && <label className={ui.field}><span>{text("执行技能")}</span>
        <Select value={String(node.config.skillVersionId ?? "")} onChange={(event) => onChange({...node.config, skillVersionId: event.target.value || null})}>
          <option value="">{text("请选择技能")}</option>
          {node.config.skillVersionId && !options?.skills.some((skill) => skill.versionId === node.config.skillVersionId) ?
            <option value={String(node.config.skillVersionId)} disabled>{text("原技能当前不可选")}</option> : null}
          {options?.skills.map((skill) => <option key={skill.versionId} value={skill.versionId}>{skill.name}{text(" · 版本 ")}{skill.versionNo}</option>)}
        </Select>
      </label>}
      {plugin && selected && options && <>
        <label className={ui.field}><span>{text("执行工具")}</span><Select value={selectedKey} onChange={(event) => {
          const tool = tools.find((item) => (item.entryId ?? item.id) === event.target.value);
          changeTool({toolId: event.target.value || null, inputMapping: tool ? parameterDefaults(tool.inputSchema, parameterObject(node.config.inputMapping)) : node.config.inputMapping});
        }}>
          <option value="">{text("请选择工具")}</option>
          {selectedKey && !selectedTool ? <option value={selectedKey} disabled>{text("原工具当前不可选")}</option> : null}
          {tools.map((tool) => <option key={tool.entryId ?? tool.id} value={tool.entryId ?? tool.id ?? ""}>{tool.displayName || tool.name}{tool.sourceName ? ` · ${tool.sourceName}` : ""}</option>)}
        </Select></label>
        {selectedTool && <>
          <WorkflowSchemaFields key={`${selected}:${selectedKey}`} schema={selectedTool.inputSchema} value={parameterObject(node.config.inputMapping)}
            variables={variables} onChange={(inputMapping) => onChange({...node.config, inputMapping})}/>
        </>}
      </>}
    </LoadingTransition>
  </div>;
}
