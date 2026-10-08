"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Checkbox} from "@/components/ui/checkbox";

import type {EditorSection} from "../lib/editor-sections";
import type {
  AgentConfig,
  DataConfig,
  KnowledgeConfig,
  PluginConfig,
  ResourceConfig,
  ResourceKind,
  ResourceSummary,
  SkillConfig,
  WorkflowConfig
} from "../types/resource";
import {WorkflowEditor} from "@/features/workflow/components/workflow-editor";
import {WorkflowDevelopmentNotice, workflowInDevelopment} from "@/features/workflow/components/workflow-development";
import {type FieldErrors, NumberField, Section, TextField} from "./resource-fields";
import {AgentConfigForm} from "./agent-config-form";
import {VersionPicker} from "./version-picker";
import {PluginConfigForm} from "@/features/plugin/components/plugin-config-form";
import {DataConfigForm} from "@/features/data/components/data-config-form";
import ui from "@/components/ui/surface.module.css";

export function ResourceConfigForm({
                                     enterpriseId,
                                     kind,
                                     config,
                                     onChange,
                                     permissions,
                                     errors,
                                     readOnly = false,
                                     resource,
                                     busy,
                                     section
                                   }: {
  enterpriseId: string;
  kind: ResourceKind;
  config: ResourceConfig;
  onChange: (value: ResourceConfig) => void;
  permissions: string[];
  errors: FieldErrors;
  readOnly?: boolean;
  resource?: ResourceSummary;
  busy?: boolean;
  section?: EditorSection;
}) {
  const uiText = useT();
  if (kind === "agent") {
    return <AgentConfigForm section={section} enterpriseId={enterpriseId} resourceId={resource?.id}
                            value={config as AgentConfig} onChange={onChange} permissions={permissions} errors={errors}
                            readOnly={readOnly}/>;
  }
  if (kind === "skill") {
    return <SkillForm section={section} enterpriseId={enterpriseId} value={config as SkillConfig} onChange={onChange}
                      permissions={permissions} errors={errors} readOnly={readOnly}/>;
  }
  if (kind === "knowledge") {
    const value = config as KnowledgeConfig;
    return <Section title={uiText("知识检索设置")}><TextField label={uiText("资料说明")} name="config.description"
                                                              value={value.description} maximum={2000} multiline
                                                              errors={errors} onChange={(description) => onChange({
      ...value,
      description
    })}/>
      <div className={ui.columns}><NumberField label={uiText("每次最多返回的段落数")} name="config.maxResults"
                                               value={value.maxResults} min={1} max={8} errors={errors}
                                               onChange={(maxResults) => onChange({...value, maxResults})}/>
        <NumberField label={uiText("每次引用的文字上限")} name="config.maxContextCharacters"
                     value={value.maxContextCharacters} min={1000} max={8000} errors={errors}
                     onChange={(maxContextCharacters) => onChange({...value, maxContextCharacters})}/></div>
    </Section>;
  }
  if (kind === "plugin") {
    return <PluginConfigForm enterpriseId={enterpriseId} value={config as PluginConfig} onChange={onChange}
                             permissions={permissions} errors={errors} readOnly={readOnly}/>;
  }
  if (kind === "data") {
    return <DataConfigForm enterpriseId={enterpriseId} value={config as DataConfig} onChange={onChange}
                           permissions={permissions} errors={errors} readOnly={readOnly}/>;
  }
  return workflowInDevelopment ? <WorkflowDevelopmentNotice/> :
    <WorkflowEditor enterprise={enterpriseId} value={config as WorkflowConfig} onChange={onChange}
                    permissions={permissions} readOnly={readOnly} resource={resource} busy={busy}/>;
}

function SkillForm({enterpriseId, value, onChange, permissions, errors, readOnly, section}: {
  enterpriseId: string;
  value: SkillConfig;
  onChange: (value: SkillConfig) => void;
  permissions: string[];
  errors: FieldErrors;
  readOnly: boolean;
  section?: EditorSection
}) {
  const uiText = useT();
  return <>
    {(!section || section === "content") && <Section title={uiText("技能说明")}>{([
      ["scenario", uiText("适用场景"), 1000], ["inputDescription", uiText("输入说明"), 2000], ["instructions", uiText("执行指令"), 20000], ["outputDescription", uiText("输出说明"), 2000], ["example", uiText("使用示例"), 5000],
    ] as const).map(([key, label, maximum]) => <TextField key={key} label={label} name={`config.${key}`}
                                                          value={value[key]}
                                                          onChange={(next) => onChange({...value, [key]: next})}
                                                          maximum={maximum} multiline required={key === "instructions"}
                                                          errors={errors}/>)}
      <label className={ui.check}><Checkbox checked={value.showInWorkspace} onCheckedChange={(checked) => onChange({
        ...value,
        showInWorkspace: checked
      })}/>{uiText("在工作台展示此技能")}</label>
    </Section>}
    {(!section || section === "capabilities") && <Section title={uiText("需要的能力")}>
      <VersionPicker enterpriseId={enterpriseId} kind="plugin" title={uiText("插件")} selected={value.pluginVersionIds}
                     onChange={(pluginVersionIds) => onChange({...value, pluginVersionIds})} maximum={10}
                     allowed={!readOnly && permissions.includes("plugin.invoke")} readOnly={readOnly}/>
      <VersionPicker enterpriseId={enterpriseId} kind="knowledge" title={uiText("知识库")}
                     selected={value.knowledgeVersionIds}
                     onChange={(knowledgeVersionIds) => onChange({...value, knowledgeVersionIds})} maximum={10}
                     allowed={!readOnly && permissions.includes("knowledge.search")} readOnly={readOnly}/>
    </Section>}
  </>;
}
