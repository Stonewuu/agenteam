"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import type {TransformField} from "../types/workflow";
import {VariableField, type VariableOption} from "./workflow-value-fields";
import {WorkflowValueEditor} from "./workflow-value-editor";
import {WorkflowVariablePicker} from "./workflow-variable-picker";
import {uniqueParameterName} from "../lib/workflow-parameters";
import form from "./workflow-parameters.module.css";

export function WorkflowTransformEditor({fields, onChange, variables}: {
  fields: TransformField[]; onChange: (value: TransformField[]) => void; variables: VariableOption[];
}) {
  const text = useT();
  const change = (index: number, value: TransformField) => onChange(fields.map((field, offset) => offset === index ? value : field));
  const names = Object.fromEntries(fields.map((field) => [field.target, true]));
  return <section className={form.stack}><div className={form.heading}><strong>{text("整理后的字段")}</strong></div>
    {fields.map((field, index) => {
      const mode = field.source !== undefined ? "source" : field.template !== undefined ? "template" : "literal";
      return <div key={index} className={form.row}>
        <div className={form.inline}><Input aria-label={text("输出字段名称")} value={field.target} maxLength={128} required placeholder={text("输出字段名称")}
          onChange={(event) => {
            const name = event.target.value;
            if (name !== field.target && Object.hasOwn(names, name)) {
              event.target.setCustomValidity(text("字段名称不能重复。"));
              event.target.reportValidity();
              return;
            }
            event.target.setCustomValidity("");
            change(index, {...field, target: name});
          }}/><Button className={form.textAction} type="button" aria-label={text("删除输出字段 {0}", [field.target || index + 1])}
          onClick={() => onChange(fields.filter((_, offset) => offset !== index))}>{text("删除")}</Button></div>
        <Select aria-label={text("取值方式")} value={mode} onChange={(event) => change(index,
          event.target.value === "source" ? {target: field.target, source: variables.find((item) => item.path.startsWith("input."))?.path ?? "input"}
            : event.target.value === "template" ? {target: field.target, template: ""} : {target: field.target, literal: ""})}>
          <option value="source">{text("引用变量")}</option><option value="template">{text("组合文字")}</option><option value="literal">{text("固定内容")}</option>
        </Select>
        {mode === "source" ? <VariableField label={text("来源字段")} value={field.source!} variables={variables} maximum={200}
          onChange={(source) => change(index, {target: field.target, source})}/> : mode === "template" ?
          <VariableField label={text("文字内容")} value={field.template!} variables={variables} template multiline onChange={(template) => change(index, {target: field.target, template})}/> :
          <WorkflowValueEditor label={text("固定内容")} value={field.literal} variables={variables} allowReferences={false}
            onChange={(literal) => change(index, {target: field.target, literal})}/>}
      </div>;
    })}
    <div className={form.actions}><WorkflowVariablePicker compact label={text("从已有数据添加")} variables={variables} onChange={(path) => {
      if (fields.length < 100) {
        onChange([...fields, {target: uniqueParameterName(names, path), source: path}]);
      }
    }}/><Button className={form.textAction} type="button" disabled={fields.length >= 100}
      onClick={() => onChange([...fields, {target: uniqueParameterName(names), literal: ""}])}>{text("添加自定义字段")}</Button></div>
  </section>;
}
