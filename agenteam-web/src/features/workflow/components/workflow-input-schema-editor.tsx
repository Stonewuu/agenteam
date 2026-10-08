"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {Select} from "@/components/ui/select";


import {useState} from "react";
import {record} from "../lib/workflow-graph";
import {JsonField} from "./workflow-value-fields";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

export function WorkflowInputSchemaEditor({value, onChange}: {
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void
}) {
  const uiText = useT();
  const [advanced, setAdvanced] = useState(false), properties = record(value.properties),
    required = Array.isArray(value.required) ? value.required as string[] : [];
  const change = (index: number, key: string, field: Record<string, unknown>, mandatory: boolean) => {
    const previous = Object.keys(properties)[index];
    onChange({
      ...value,
      properties: Object.fromEntries(Object.entries(properties).map((entry, offset) => offset === index ? [key, field] : entry)),
      required: [...required.filter((name) => name !== previous), ...(mandatory ? [key] : [])]
    });
  };
  return <section className={styles.section}>
    <div className={styles.heading}><h4>{uiText("输入字段")}</h4><Button className={ui.button} type="button"
                                                                         onClick={() => setAdvanced((value) => !value)}>{advanced ? uiText("按字段编辑") : uiText("编辑完整结构")}</Button>
    </div>
    {advanced ? <JsonField label={uiText("输入结构（JSON Schema）")} value={value} object
                           onChange={(next) => onChange(next as Record<string, unknown>)} rows={10}/> :
      <div className={styles.mapping}>
        {Object.entries(properties).map(([key, raw], index) => {
          const field = record(raw);
          return <div className={styles.mappingRow} key={index}>
            <div className={styles.line}><label className={ui.field}><span>{uiText("字段名称")}</span><Input
              className={ui.input} value={key} required maxLength={128} onChange={(event) => {
              const name = event.target.value;
              if (name !== key && Object.hasOwn(properties, name)) {
                event.target.setCustomValidity(uiText("字段名称不能重复。"));
                event.target.reportValidity();
                return;
              }
              event.target.setCustomValidity("");
              change(index, name, field, required.includes(key));
            }}/></label><Button type="button" className={ui.danger}
                                aria-label={uiText("删除输入字段 {0}", [key || index + 1])} onClick={() => onChange({
              ...value,
              properties: Object.fromEntries(Object.entries(properties).filter(([name]) => name !== key)),
              required: required.filter((name) => name !== key)
            })}>{uiText("删除")}</Button></div>
            <label className={ui.field}><span>{uiText("显示名称")}</span><Input className={ui.input}
                                                                                value={String(field.title ?? "")}
                                                                                maxLength={100}
                                                                                onChange={(event) => change(index, key, {
                                                                                  ...field,
                                                                                  title: event.target.value
                                                                                }, required.includes(key))}/></label>
            <label className={ui.field}><span>{uiText("数据类型")}</span><Select className={ui.select}
                                                                                 value={String(field.type ?? "string")}
                                                                                 onChange={(event) => {
                                                                                   const type = event.target.value;
                                                                                   change(index, key,
                                                                                     {type, ...(field.title ? {title: field.title} : {}), ...(type === "object" ? {properties: {}} : type === "array" ? {items: {type: "string"}} : {})}, required.includes(key));
                                                                                 }}>
              <option value="string">{uiText("文字")}</option>
              <option value="number">{uiText("数字")}</option>
              <option value="integer">{uiText("整数")}</option>
              <option value="boolean">{uiText("是或否")}</option>
              <option value="object">{uiText("对象")}</option>
              <option value="array">{uiText("列表")}</option>
            </Select></label>
            <label className={ui.check}><Checkbox checked={required.includes(key)}
                                                  onCheckedChange={(checked) => change(index, key, field, checked)}/>{uiText("必填")}
            </label>
          </div>;
        })}
        <Button type="button" className={ui.button} disabled={Object.keys(properties).length >= 100} onClick={() => {
          let index = 1;
          while (Object.hasOwn(properties, `field${index}`)) {
            index++;
          }
          onChange({
            ...value,
            type: "object",
            properties: {...properties, [`field${index}`]: {type: "string", title: ""}}
          });
        }}>{uiText("添加输入字段")}</Button>
      </div>}
  </section>;
}
