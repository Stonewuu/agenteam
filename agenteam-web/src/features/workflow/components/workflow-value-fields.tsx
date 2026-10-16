"use client";

import {useId, useState} from "react";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {useT} from "@/lib/i18n/locale-provider";
import {referenceValue, uniqueParameterName, type VariableOption} from "../lib/workflow-parameters";
import {WorkflowVariablePicker} from "./workflow-variable-picker";
import {WorkflowAdvanced, WorkflowValueEditor} from "./workflow-value-editor";
import {JsonField} from "./workflow-json-field";
import form from "./workflow-parameters.module.css";

export {JsonField} from "./workflow-json-field";
export type {VariableOption} from "../lib/workflow-parameters";

export function VariableField({label, value, onChange, variables, template = false, multiline = false, maximum = 20000}: {
  label: string; value: string; onChange: (value: string) => void; variables: VariableOption[];
  template?: boolean; multiline?: boolean; maximum?: number;
}) {
  const text = useT(), id = useId();
  const [custom, setCustom] = useState(false);
  const known = variables.some((item) => item.path === value);
  if (template) {
    return <WorkflowValueEditor label={label} value={value} variables={variables} schema={{type: "string", maxLength: maximum}}
      multiline={multiline} onChange={(next) => onChange(String(next ?? ""))}/>;
  }
  return <div className={form.stack}><div className={form.heading}><label htmlFor={id}>{label}</label>
    <Button type="button" className={form.textAction} onClick={() => setCustom(!custom)}>{custom ? text("选择已有数据") : text("自定义路径")}</Button></div>
    {custom || value && !known ? <Input id={id} value={value} maxLength={maximum} aria-label={label} onChange={(event) => onChange(event.target.value)}/> : null}
    <WorkflowVariablePicker label={text("为{0}选择变量", [label])} value={known ? value : ""} variables={variables} onChange={(path) => {
      setCustom(false);
      onChange(path);
    }}/>
  </div>;
}

export function MappingEditor({label, value, onChange, variables, compact = false}: {
  label: string; value: Record<string, unknown>; onChange: (value: Record<string, unknown>) => void; variables: VariableOption[]; compact?: boolean;
}) {
  const text = useT();
  const change = (index: number, key: string, content: unknown) => onChange(Object.fromEntries(Object.entries(value).map((entry, offset) => offset === index ? [key, content] : entry)));
  return <section className={form.stack}>
    <div className={form.heading}><strong>{label}</strong></div>
    {Object.entries(value).map(([key, content], index) => <div key={index} className={form.row}>
      <div className={form.inline}><Input aria-label={text("字段名称")} value={key} maxLength={128} required placeholder={text("字段名称")}
        onChange={(event) => {
          const name = event.target.value;
          if (name !== key && Object.hasOwn(value, name)) {
            event.target.setCustomValidity(text("字段名称不能重复。"));
            event.target.reportValidity();
            return;
          }
          event.target.setCustomValidity("");
          change(index, name, content);
        }}/><Button type="button" className={form.textAction} aria-label={text("删除字段 {0}", [key || index + 1])}
          onClick={() => onChange(Object.fromEntries(Object.entries(value).filter((_, offset) => offset !== index)))}>{text("删除")}</Button></div>
      <WorkflowValueEditor label={text("内容")} value={content} variables={variables} onChange={(next) => change(index, key, next)} multiline/>
    </div>)}
    <div className={form.actions}>
      <WorkflowVariablePicker compact label={text("从已有数据添加")} variables={variables} onChange={(path) => {
        if (Object.keys(value).length < 100) {
          onChange({...value, [uniqueParameterName(value, path)]: referenceValue(path)});
        }
      }}/>
      <Button type="button" className={form.textAction} disabled={Object.keys(value).length >= 100}
        onClick={() => onChange({...value, [uniqueParameterName(value)]: ""})}>{text("添加自定义字段")}</Button>
    </div>
    {!compact && <WorkflowAdvanced title={text("编辑完整对象")}><JsonField label={text("{0}（JSON 对象）", [label])} value={value} object
      onChange={(next) => onChange(next as Record<string, unknown>)}/></WorkflowAdvanced>}
  </section>;
}
