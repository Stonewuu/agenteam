"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import type {TransformField} from "../types/workflow";
import {JsonField, VariableField, type VariableOption} from "./workflow-value-fields";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

export function WorkflowTransformEditor({fields, onChange, variables}: {
  fields: TransformField[];
  onChange: (value: TransformField[]) => void;
  variables: VariableOption[]
}) {
  const uiText = useT();
  const change = (index: number, value: TransformField) => onChange(fields.map((field, offset) => offset === index ? value : field));
  return <section className={styles.section}><h4>{uiText("整理后的字段")}</h4>
    <div className={styles.mapping}>
      {fields.map((field, index) => {
        const mode = field.source !== undefined ? "source" : field.template !== undefined ? "template" : "literal";
        return <div key={index} className={styles.mappingRow}>
          <div className={styles.line}><label className={ui.field}><span>{uiText("输出字段名称")}</span><Input
            className={ui.input} value={field.target} maxLength={128} required
            onChange={(event) => change(index, {...field, target: event.target.value})}/></label>
            <Button className={ui.danger} type="button"
                    aria-label={uiText("删除输出字段 {0}", [field.target || index + 1])}
                    onClick={() => onChange(fields.filter((_, offset) => offset !== index))}>{uiText("删除")}</Button>
          </div>
          <label className={ui.field}><span>{uiText("取值方式")}</span><Select className={ui.select} value={mode}
                                                                               onChange={(event) => change(index, event.target.value === "source" ? {
                                                                                 target: field.target,
                                                                                 source: "input.text"
                                                                               } : event.target.value === "template" ? {
                                                                                 target: field.target,
                                                                                 template: ""
                                                                               } : {
                                                                                 target: field.target,
                                                                                 literal: ""
                                                                               })}>
            <option value="source">{uiText("读取字段")}</option>
            <option value="template">{uiText("组合文字")}</option>
            <option value="literal">{uiText("固定内容")}</option>
          </Select></label>
          {mode === "source" ? <VariableField label={uiText("来源字段")} value={field.source!} variables={variables}
                                              onChange={(source) => change(index, {target: field.target, source})}
                                              maximum={200}/>
            : mode === "template" ?
              <VariableField label={uiText("文字内容")} value={field.template!} variables={variables}
                             onChange={(template) => change(index, {target: field.target, template})} template
                             multiline/>
              : <JsonField label={uiText("固定内容")} value={field.literal}
                           onChange={(literal) => change(index, {target: field.target, literal})} rows={2}/>}
        </div>;
      })}
      <Button className={ui.button} type="button" disabled={fields.length >= 100} onClick={() => onChange([...fields, {
        target: `field${fields.length + 1}`,
        source: "input.text"
      }])}>{uiText("添加输出字段")}</Button>
    </div>
  </section>;
}
