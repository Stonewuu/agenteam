"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Checkbox} from "@/components/ui/checkbox";
import {Input} from "@/components/ui/input";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";

import type {EditorSection} from "../lib/editor-sections";
import {Select} from "@/components/ui/select";


import {useState} from "react";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {type ApiPage, useApiQuery} from "@/lib/http/use-api-query";
import type {AgentConfig, ModelProfile, ResourceKind, UsableVersion} from "../types/resource";
import {type FieldErrors, NumberField, Section, StringListField, TextField} from "./resource-fields";
import {VersionPicker} from "./version-picker";
import {BusinessTermsField} from "./business-terms-field";
import {AgentSubagentSettings} from "./agent-subagent-settings";
import {type ReasoningEffort, reasoningEffortLabels} from "@/features/modelprofile/lib/reasoning-effort";
import {WorkflowDevelopmentNotice, workflowInDevelopment} from "@/features/workflow/components/workflow-development";
import ui from "@/components/ui/surface.module.css";

export function AgentConfigForm({
                                  enterpriseId,
                                  resourceId,
                                  value,
                                  onChange,
                                  permissions,
                                  errors,
                                  readOnly = false,
                                  section
                                }: {
  enterpriseId: string;
  resourceId?: string;
  value: AgentConfig;
  onChange: (value: AgentConfig) => void;
  permissions: string[];
  errors: FieldErrors;
  readOnly?: boolean;
  section?: EditorSection;
}) {
  const uiText = useT();
  const [typeChange, setTypeChange] = useState<AgentConfig["agentType"] | null>(null);
  const profiles = useApiQuery<ModelProfile[]>(organizationPath(enterpriseId, "/model-profiles"));
  const model = profiles.data?.find((model) => model.id === value.modelProfileId);
  const set = <K extends keyof AgentConfig>(key: K, next: AgentConfig[K]) => onChange({...value, [key]: next});
  const canUse = (kind: ResourceKind) => permissions.includes(({
    agent: "agent.run",
    workflow: "agent.run",
    skill: "skill.use",
    plugin: "plugin.invoke",
    knowledge: "knowledge.search",
    data: "data.query"
  })[kind]);
  const canReadVersions = permissions.some((permission) => /^(agent|skill|plugin|workflow|knowledge|data)\.(create|edit|preview)$/.test(permission));
  const switchType = () => {
    if (!typeChange || typeChange === "workflow" && workflowInDevelopment) {
      return;
    }
    const next = {
      ...value,
      agentType: typeChange,
      entryWorkflowVersionId: typeChange === "workflow" ? value.workflowVersionIds[0] ?? null : null
    };
    if (typeChange === "workflow") {
      next.modelProfileId = null;
      delete next.temperature;
      delete next.reasoningEffort;
      next.subagentVersionIds = [];
      next.dynamicSubagentEnabled = false;
    }
    if (typeChange === "task") {
      next.historyMessageLimit = 0;
    }
    onChange(next);
    setTypeChange(null);
  };
  return <>
    {(!section || section === "basic") && <Section title={section === "basic" ? undefined : uiText("工作方式")}>
      <TextField label={uiText("业务职责")} name="config.businessRole" required value={value.businessRole}
                 onChange={(value) => set("businessRole", value)} maximum={200} errors={errors}/>
      <div className={ui.columns}><label className={ui.field}><span>{uiText("智能体类型")}</span><Select
        className={ui.select} value={value.agentType}
        onChange={(event) => setTypeChange(event.target.value as AgentConfig["agentType"])}>
        <option value="chat">{uiText("对话型")}</option>
        <option value="task">{uiText("任务型")}</option>
        <option value="workflow"
                disabled={workflowInDevelopment}>{workflowInDevelopment ? uiText("流程型（开发中）") : uiText("流程型")}</option>
      </Select></label>
        {value.agentType !== "workflow" &&
          <QueryState {...profiles} hasData={Boolean(profiles.data?.length)}
                      empty={uiText("暂无可选模型，请联系企业管理员配置。")}>
            <label className={ui.field}><span>{uiText("默认模型（必填）")}</span><Select className={ui.select}
                                                                                       name="config.modelProfileId"
                                                                                       value={value.modelProfileId ?? ""}
                                                                                       required
                                                                                       aria-invalid={Boolean(errors["config.modelProfileId"])}
                                                                                       onChange={(event) => {
                                                                                         const profile = profiles.data?.find((profile) => profile.id === event.target.value);
                                                                                         const next = {
                                                                                           ...value,
                                                                                           modelProfileId: profile?.id ?? null
                                                                                         };
                                                                                         if (!profile?.capabilities.supportsTemperature) {
                                                                                           delete next.temperature;
                                                                                         }
                                                                                         if (next.reasoningEffort && !profile?.capabilities.reasoningEfforts?.includes(next.reasoningEffort)) {
                                                                                           delete next.reasoningEffort;
                                                                                         }
                                                                                         onChange(next);
                                                                                       }}>
              <option value="">{uiText("请选择模型")}</option>
              {value.modelProfileId && !model &&
                <option value={value.modelProfileId} disabled>{uiText("原模型当前不可选")}</option>}
              {profiles.data?.map((profile) => <option key={profile.id} value={profile.id}
                                                       disabled={!profile.enabled}>{profile.name}{profile.enabled ? "" : uiText("（已停用）")}</option>)}
            </Select>
              <FieldErrorFeedback messages={errors["config.modelProfileId"]}/><small
                className={ui.description}>{uiText("用于新对话，用户可在对话中切换。")}</small></label>
          </QueryState>}
      </div>
      {value.agentType !== "workflow" && <>
        {(Boolean(model?.capabilities.reasoningEfforts?.length) || value.reasoningEffort) &&
          <label className={ui.field}><span>{uiText("默认思考强度")}</span>
            <Select className={ui.select} name="config.reasoningEffort" value={value.reasoningEffort ?? ""}
                    aria-invalid={Boolean(errors["config.reasoningEffort"])}
                    onChange={(event) => set("reasoningEffort", event.target.value as ReasoningEffort || null)}>
              <option value="">{uiText("使用模型默认值")}</option>
              {value.reasoningEffort && !model?.capabilities.reasoningEfforts?.includes(value.reasoningEffort) &&
                <option value={value.reasoningEffort} disabled>{uiText("原等级不可用")}</option>}
              {model?.capabilities.reasoningEfforts?.map((effort) => <option key={effort}
                                                                             value={effort}>{localizeCatalog(reasoningEffortLabels, uiText)[effort]}</option>)}
            </Select><FieldErrorFeedback messages={errors["config.reasoningEffort"]}/>
          </label>}
        <TextField label={uiText("执行指令")} name="config.instructions" value={value.instructions}
                   onChange={(value) => set("instructions", value)} maximum={20000} multiline required errors={errors}/>

      </>}
    </Section>}
    {(!section || section === "capabilities") && <Section title={uiText("能力与资料")}>
      {([
        ["skill", uiText("技能"), "skillVersionIds", 20], ["plugin", uiText("插件"), "pluginVersionIds", 20], ["knowledge", uiText("知识库"), "knowledgeVersionIds", 10],
        ["data", uiText("数据源"), "dataVersionIds", 10], ["workflow", uiText("工作流"), "workflowVersionIds", 5],
      ] as const).map(([kind, title, field, maximum]) => kind === "workflow" && workflowInDevelopment ?
        <WorkflowDevelopmentNotice key={kind}/> :
        <VersionPicker key={kind} enterpriseId={enterpriseId} kind={kind} title={title} selected={value[field]}
                       onChange={(selected) => {
                         const next = {...value, [field]: selected};
                         if (field === "workflowVersionIds" && next.entryWorkflowVersionId && !selected.includes(next.entryWorkflowVersionId)) {
                           next.entryWorkflowVersionId = null;
                         }
                         onChange(next);
                       }}
                       maximum={maximum} allowed={canUse(kind) && !readOnly} readOnly={readOnly}/>)}
      {value.agentType === "workflow" && !workflowInDevelopment &&
        <EntryWorkflow enterpriseId={enterpriseId} value={value} onChange={(id) => set("entryWorkflowVersionId", id)}
                       allowed={canUse("workflow") && !readOnly}/>}
    </Section>}
    {(!section || section === "experience") && <Section title={uiText("对话体验")}>
      <TextField label={uiText("欢迎语")} name="config.welcomeMessage" value={value.welcomeMessage}
                 onChange={(value) => set("welcomeMessage", value)} maximum={1000} multiline errors={errors}/>
      <StringListField label={uiText("建议问题")} name="config.suggestedQuestions" values={value.suggestedQuestions}
                       onChange={(value) => set("suggestedQuestions", value)} maximum={5} length={200} errors={errors}/>
      <StringListField label={uiText("典型工作")} name="config.publicExamples" values={value.publicExamples}
                       onChange={(value) => set("publicExamples", value)} maximum={5} length={200} errors={errors}/>
      <label className={ui.check}><Checkbox checked={value.attachmentsEnabled}
                                            onCheckedChange={(checked) => set("attachmentsEnabled", checked)}/>{uiText("  允许用户附加资料")}
      </label>
      {value.agentType === "chat" && <NumberField label={uiText("带入的历史消息条数")} name="config.historyMessageLimit"
                                                  value={value.historyMessageLimit} min={0} max={100}
                                                  onChange={(value) => set("historyMessageLimit", value)}
                                                  errors={errors}/>}
    </Section>}
    {(!section || section === "advanced") && <Section title={uiText("执行设置")}>
      {model?.capabilities.supportsTemperature &&
        <label className={ui.field}><span>{uiText("回复变化程度")}</span><Select className={ui.select}
                                                                                 value={value.temperature === undefined ? "default" : "custom"}
                                                                                 onChange={(event) => {
                                                                                   const next = {...value};
                                                                                   if (event.target.value === "default") {
                                                                                     delete next.temperature;
                                                                                   } else {
                                                                                     next.temperature = .7;
                                                                                   }
                                                                                   onChange(next);
                                                                                 }}>
          <option value="default">{uiText("使用模型默认值")}</option>
          <option value="custom">{uiText("自行设置")}</option>
        </Select>
          {value.temperature !== undefined &&
            <Input className={ui.input} type="number" min={0} max={2} step={.1} value={value.temperature}
                   aria-label={uiText("回复变化程度")}
                   onChange={(event) => set("temperature", event.target.valueAsNumber)}/>}</label>}
      <div className={ui.columns}><NumberField label={uiText("最多执行步骤")} name="config.maxSteps"
                                               value={value.maxSteps} min={0} hint={uiText("填写 0 表示无上限。")}
                                               onChange={(value) => set("maxSteps", value)} errors={errors}/>
        <NumberField label={uiText("执行时间上限（秒）")} name="config.timeoutSeconds" value={value.timeoutSeconds}
                     min={0} hint={uiText("填写 0 表示无上限。")} onChange={(value) => set("timeoutSeconds", value)}
                     errors={errors}/></div>
      {value.agentType !== "workflow" && (model?.capabilities.supportsTools || Boolean(value.subagentVersionIds?.length || value.dynamicSubagentEnabled)) &&
        <AgentSubagentSettings enterpriseId={enterpriseId} resourceId={resourceId}
                               allowed={canUse("agent") && canReadVersions} value={value} onChange={onChange}
                               errors={errors} readOnly={readOnly}/>}
      <label className={ui.check}><Checkbox checked={value.memoryEnabled}
                                            onCheckedChange={(checked) => set("memoryEnabled", checked)}/>{uiText("允许使用用户明确保存的偏好")}
      </label>
      {value.memoryEnabled &&
        <StringListField label={uiText("偏好主题")} name="config.memoryFields" values={value.memoryFields}
                         onChange={(value) => set("memoryFields", value)} maximum={20} length={50} errors={errors}/>}
      <BusinessTermsField values={value.businessTerms} onChange={(terms) => set("businessTerms", terms)}
                          errors={errors}/>
    </Section>}
    {typeChange && <Dialog title={uiText("切换智能体类型？")} onClose={() => setTypeChange(null)}><p
      className={ui.description}>{typeChange === "workflow" ? uiText("切换为流程型后，将清除默认模型、思考强度、回复变化程度和子智能体设置，并使用选定工作流。") : typeChange === "task" ? uiText("切换为任务型后，将清除入口工作流设置，并设为不带入历史消息。") : uiText("切换为对话型后，将清除入口工作流设置。")}</p>
      <DialogActions className={ui.footer}><DialogCancel
        className={ui.button}>{uiText("取消")}</DialogCancel><DialogAction className={ui.primary}
                                                                           onAction={(close) => close(switchType)}>{uiText("确认切换")}</DialogAction></DialogActions></Dialog>}
  </>;
}

function EntryWorkflow({enterpriseId, value, onChange, allowed}: {
  enterpriseId: string;
  value: AgentConfig;
  onChange: (value: string | null) => void;
  allowed: boolean
}) {
  const uiText = useT();
  const options = useApiQuery<ApiPage<UsableVersion>>(allowed && value.workflowVersionIds.length ? organizationPath(enterpriseId, `/resources/usable-versions?kind=workflow&versionIds=${encodeURIComponent(value.workflowVersionIds.join(","))}`) : null);
  return <label className={ui.field}><span>{uiText("入口工作流")}</span><Select className={ui.select}
                                                                                value={value.entryWorkflowVersionId ?? ""}
                                                                                onChange={(event) => onChange(event.target.value || null)}>
    <option value="">{uiText("请选择入口工作流")}</option>
    {options.data?.items.map((workflow) => <option key={workflow.versionId}
                                                   value={workflow.versionId}>{workflow.name}{uiText(" · 版本 ")}{workflow.versionNo}</option>)}
  </Select></label>;
}
