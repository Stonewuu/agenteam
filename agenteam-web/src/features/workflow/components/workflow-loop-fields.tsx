"use client";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {IconArrowRight} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {JsonField, type VariableOption} from "./workflow-value-fields";
import {WorkflowAdvanced} from "./workflow-value-editor";
import {WorkflowVariablePicker} from "./workflow-variable-picker";
import {referencePath, referenceValue} from "../lib/workflow-parameters";
import type {WorkflowNode} from "../types/workflow";
import ui from "@/components/ui/surface.module.css";
import form from "./workflow-parameters.module.css";

export function WorkflowLoopFields({node, variables, onChange, onOpen}: {
  node: WorkflowNode; variables: VariableOption[]; onChange: (config: WorkflowNode["config"]) => void; onOpen: () => void;
}) {
  const text = useT(), config = node.config;
  const change = (key: string, value: unknown) => onChange({...config, [key]: value});
  return <section className={form.stack}>
    <label className={ui.field}><span>{text("循环方式")}</span><Select value={String(config.mode)} onChange={(event) => change("mode", event.target.value)}>
      <option value="count">{text("重复指定次数")}</option><option value="items">{text("逐项处理列表")}</option>
    </Select></label>
    {config.mode === "count" ? <label className={ui.field}><span>{text("执行次数")}</span>
      <Input type="number" min={1} max={20} value={Number(config.count ?? 3)} required onChange={(event) => {
        const count = Number(event.target.value);
        onChange({...config, count, maxIterations: Math.max(Number(config.maxIterations), count)});
      }}/></label> : <>
      <label className={ui.field}><span>{text("列表来源")}</span><Select value={Array.isArray(config.items) ? "literal" : "variable"}
        onChange={(event) => change("items", event.target.value === "literal" ? [] : referenceValue(variables.find((item) => item.schema?.type === "array")?.path ?? "input.items"))}>
        <option value="variable">{text("输入或上游步骤")}</option><option value="literal">{text("固定列表")}</option>
      </Select></label>
      {Array.isArray(config.items) ? <JsonField label={text("要处理的列表")} value={config.items} array onChange={(value) => change("items", value)} rows={4}/> :
        <WorkflowVariablePicker label={text("要处理的列表")} variables={variables.filter((item) => !item.schema?.type || item.schema.type === "array")}
          value={referencePath(config.items)} onChange={(path) => change("items", referenceValue(path))}/>}
      <WorkflowAdvanced title={text("循环上限")}><label className={ui.field}><span>{text("最多执行次数")}</span><Input type="number" min={1} max={20} required
        value={Number(config.maxIterations)} onChange={(event) => change("maxIterations", Number(event.target.value))}/></label></WorkflowAdvanced>
    </>}
    <Button type="button" className={ui.button} onClick={onOpen}>{text("编辑循环内的步骤")}<IconArrowRight size={16}/></Button>
  </section>;
}
