"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import type {Condition, TransformField, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {record, variableOptions} from "../lib/workflow-graph";
import {useWorkflowDependencies} from "../hooks/use-workflow-dependencies";
import {MappingEditor, VariableField, type VariableOption} from "./workflow-value-fields";
import {WorkflowAdvanced, WorkflowValueEditor} from "./workflow-value-editor";
import {WorkflowCapabilityFields} from "./workflow-capability-fields";
import {WorkflowConditionEditor} from "./workflow-condition-editor";
import {WorkflowTransformEditor} from "./workflow-transform-editor";
import {WorkflowInputSchemaEditor} from "./workflow-input-schema-editor";
import {WorkflowLoopFields} from "./workflow-loop-fields";
import ui from "@/components/ui/surface.module.css";
import form from "./workflow-parameters.module.css";

export function WorkflowNodeFields({enterprise, graph, node, permissions, onChange, onOpenLoop, readOnly}: {
  enterprise: string; graph: WorkflowGraph; node: WorkflowNode; permissions: string[];
  onChange: (node: WorkflowNode) => void; onOpenLoop: () => void; readOnly: boolean;
}) {
  const text = useT(), config = node.config;
  const dependencies = useWorkflowDependencies(enterprise, graph, node, permissions);
  const variables = variableOptions(graph, node.nodeId, text, dependencies.data);
  const version = String(config[node.type === "tool" ? "pluginVersionId" : "agentVersionId"] ?? "");
  const change = (key: string, value: unknown) => onChange({...node, config: {...config, [key]: value}});
  return <Fieldset className={form.stack} disabled={readOnly}>
    <label className={ui.field}><span>{text("节点名称")}</span><Input value={node.name} maxLength={50} required
      onChange={(event) => onChange({...node, name: event.target.value})}/></label>
    {node.type === "start" && <WorkflowInputSchemaEditor value={record(config.inputSchema)} onChange={(value) => change("inputSchema", value)}/>}
    {["agent", "skill", "tool"].includes(node.type) && <WorkflowCapabilityFields enterprise={enterprise} node={node} permissions={permissions}
      onChange={(config) => onChange({...node, config})} readOnly={readOnly} options={dependencies.data[version]}
      loading={Boolean(version) && dependencies.loading} error={dependencies.errors[version]} retry={dependencies.retry} variables={variables}/>}
    {["agent", "skill"].includes(node.type) && <TaskContent label={text("任务内容")} value={record(config.inputMapping)} variables={variables}
      onChange={(value) => change("inputMapping", value)}/>}
    {node.type === "condition" && <WorkflowConditionEditor value={config.condition as Condition} variables={variables} onChange={(value) => change("condition", value)}/>}
    {node.type === "loop" && <WorkflowLoopFields node={node} variables={variables} onOpen={onOpenLoop} onChange={(config) => onChange({...node, config})}/>}
    {node.type === "transform" && <WorkflowTransformEditor fields={config.fields as TransformField[]} variables={variables} onChange={(value) => change("fields", value)}/>}
    {node.type === "approval" && <>
      <VariableField label={text("确认标题")} value={String(config.title ?? "")} variables={variables} maximum={100} template onChange={(value) => change("title", value)}/>
      <VariableField label={text("操作说明")} value={String(config.description ?? "")} variables={variables} maximum={2000} template multiline onChange={(value) => change("description", value)}/>
      <TaskContent label={text("待确认内容")} value={record(config.inputMapping)} variables={variables} onChange={(value) => change("inputMapping", value)}/>
    </>}
    {node.type === "end" && <TaskContent label={text("返回内容")} value={record(config.outputMapping)} variables={variables} onChange={(value) => change("outputMapping", value)}/>}
    {node.type === "parallel" && <p className={form.description}>{text("将输出连接到需要同时执行的步骤，再将各分支连接到同一个合并节点。")}</p>}
    {node.type === "join" && <p className={form.description}>{text("连接各条并行分支，全部完成后继续执行后续步骤。")}</p>}
    {!["start", "end", "parallel", "join", "condition"].includes(node.type) && <WorkflowAdvanced title={text("执行设置")}>
      <div className={form.stack}><label className={ui.field}><span>{node.type === "loop" ? text("循环总处理时间上限（秒）") : text("处理时间上限（秒）")}</span>
        <Input type="number" min={1} max={1800} required value={node.timeoutSeconds} onChange={(event) => {
          if (event.target.value) {
            onChange({...node, timeoutSeconds: Number(event.target.value)});
          }
        }}/></label>
        {["agent", "skill", "tool", "transform"].includes(node.type) && <label className={ui.field}><span>{text("步骤失败后")}</span>
          <Select value={node.failurePolicy} onChange={(event) => onChange({...node, failurePolicy: event.target.value as WorkflowNode["failurePolicy"]})}>
            <option value="stop">{text("停止整个流程")}</option><option value="continue">{text("记录错误并继续")}</option>
          </Select></label>}
      </div>
    </WorkflowAdvanced>}
  </Fieldset>;
}

function TaskContent({label, value, variables, onChange}: {
  label: string; value: Record<string, unknown>; variables: VariableOption[]; onChange: (value: Record<string, unknown>) => void;
}) {
  const text = useT();
  const extra = Object.fromEntries(Object.entries(value).filter(([key]) => key !== "text"));
  const hasText = Object.hasOwn(value, "text") || !Object.keys(value).length;
  return <div className={form.stack}>
    {hasText && <WorkflowValueEditor label={label} value={value.text ?? ""} variables={variables}
      schema={typeof value.text === "string" || value.text === undefined ? {type: "string"} : {}} multiline
      onChange={(content) => onChange({...value, text: content})}/>}
    <WorkflowAdvanced title={hasText ? text("补充数据") : label} initiallyOpen={!hasText || Object.keys(extra).length > 0}>
      <MappingEditor label={text("数据字段")} value={extra} variables={variables} compact onChange={(next) => onChange({...(Object.hasOwn(value, "text") ? {text: value.text} : {}), ...next})}/>
    </WorkflowAdvanced>
  </div>;
}
