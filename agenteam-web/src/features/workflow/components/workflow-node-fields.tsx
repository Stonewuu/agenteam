"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Fieldset} from "@/components/ui/fieldset";
import {Input} from "@/components/ui/input";

import {Select} from "@/components/ui/select";


import type {Condition, TransformField, WorkflowGraph, WorkflowNode} from "../types/workflow";
import {nodeNames, record, variableOptions} from "../lib/workflow-graph";
import {MappingEditor, VariableField} from "./workflow-value-fields";
import {WorkflowCapabilityFields} from "./workflow-capability-fields";
import {WorkflowConditionEditor} from "./workflow-condition-editor";
import {WorkflowTransformEditor} from "./workflow-transform-editor";
import {WorkflowInputSchemaEditor} from "./workflow-input-schema-editor";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

export function WorkflowNodeFields({enterprise, graph, node, permissions, onChange, onPair, readOnly}: {
  enterprise: string;
  graph: WorkflowGraph;
  node: WorkflowNode;
  permissions: string[];
  onChange: (node: WorkflowNode) => void;
  onPair: (id: string) => void;
  readOnly: boolean;
}) {
  const uiText = useT();
  const variables = variableOptions(graph, node.nodeId, uiText), config = node.config;
  const change = (key: string, value: unknown) => onChange({...node, config: {...config, [key]: value}});
  return <Fieldset className={styles.fields} disabled={readOnly}>
    <label className={ui.field}><span>{uiText("节点名称")}</span><Input className={ui.input} value={node.name}
                                                                        maxLength={50} required
                                                                        onChange={(event) => onChange({
                                                                          ...node,
                                                                          name: event.target.value
                                                                        })}/></label>
    {node.type === "start" && <WorkflowInputSchemaEditor value={record(config.inputSchema)}
                                                         onChange={(value) => change("inputSchema", value)}/>}
    {["agent", "skill", "tool"].includes(node.type) &&
      <WorkflowCapabilityFields enterprise={enterprise} node={node} permissions={permissions}
                                onChange={(config) => onChange({...node, config})} readOnly={readOnly}/>}
    {Object.hasOwn(config, "inputMapping") && <MappingEditor
      label={node.type === "tool" ? uiText("工具参数") : node.type === "approval" ? uiText("待确认内容") : uiText("交给智能体的内容")}
      value={record(config.inputMapping)} variables={variables} onChange={(value) => change("inputMapping", value)}/>}
    {node.type === "condition" && <WorkflowConditionEditor value={config.condition as Condition} variables={variables}
                                                           onChange={(value) => change("condition", value)}/>}
    {node.type === "transform" &&
      <WorkflowTransformEditor fields={config.fields as TransformField[]} variables={variables}
                               onChange={(value) => change("fields", value)}/>}
    {node.type === "approval" && <>
      <VariableField label={uiText("确认标题")} value={String(config.title ?? "")} variables={variables} maximum={100}
                     template onChange={(value) => change("title", value)}/>
      <VariableField label={uiText("操作说明")} value={String(config.description ?? "")} variables={variables}
                     maximum={2000} template multiline onChange={(value) => change("description", value)}/>
    </>}
    {node.type === "parallel" || node.type === "join" ? <label
      className={ui.field}><span>{node.type === "parallel" ? uiText("汇合位置") : uiText("对应的并行节点")}</span>
      <Select className={ui.select}
              value={String(config[node.type === "parallel" ? "joinNodeId" : "parallelNodeId"] ?? "")} required
              onChange={(event) => onPair(event.target.value)}>
        <option
          value="">{uiText("请选择")}{node.type === "parallel" ? uiText("汇合") : uiText("并行")}{uiText("节点")}</option>
        {graph.nodes.filter((other) => other.type === (node.type === "parallel" ? "join" : "parallel")).map((other) =>
          <option key={other.nodeId}
                  value={other.nodeId}>{other.name} · {localizeCatalog(nodeNames, uiText)[other.type]}</option>)}
      </Select></label> : null}
    {node.type === "end" &&
      <MappingEditor label={uiText("最终结果")} value={record(config.outputMapping)} variables={variables}
                     onChange={(value) => change("outputMapping", value)}/>}
    <section className={styles.section}><h4>{uiText("执行设置")}</h4><label
      className={ui.field}><span>{uiText("处理时间上限（秒）")}</span><Input className={ui.input} type="number" min={1}
                                                                           max={1800} required
                                                                           value={node.timeoutSeconds}
                                                                           onChange={(event) => {
                                                                             if (event.target.value) {
                                                                               onChange({
                                                                                 ...node,
                                                                                 timeoutSeconds: Number(event.target.value)
                                                                               });
                                                                             }
                                                                           }}/></label>
      {["agent", "skill", "tool", "transform"].includes(node.type) &&
        <label className={ui.field}><span>{uiText("步骤失败后")}</span><Select className={ui.select}
                                                                               value={node.failurePolicy}
                                                                               onChange={(event) => onChange({
                                                                                 ...node,
                                                                                 failurePolicy: event.target.value as WorkflowNode["failurePolicy"]
                                                                               })}>
          <option value="stop">{uiText("停止整个流程")}</option>
          <option value="continue">{uiText("记录错误并继续")}</option>
        </Select></label>}
    </section>
  </Fieldset>;
}
