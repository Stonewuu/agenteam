"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";
import {Select} from "@/components/ui/select";
import {record} from "../lib/workflow-graph";
import {uniqueParameterName} from "../lib/workflow-parameters";
import {JsonField} from "./workflow-value-fields";
import {WorkflowAdvanced} from "./workflow-value-editor";
import ui from "@/components/ui/surface.module.css";
import form from "./workflow-parameters.module.css";

export function WorkflowInputSchemaEditor({value, onChange}: {value: Record<string, unknown>; onChange: (value: Record<string, unknown>) => void}) {
  const text = useT(), properties = record(value.properties), required = Array.isArray(value.required) ? value.required as string[] : [];
  const change = (index: number, key: string, field: Record<string, unknown>, mandatory: boolean) => {
    const previous = Object.keys(properties)[index];
    onChange({...value, properties: Object.fromEntries(Object.entries(properties).map((entry, offset) => offset === index ? [key, field] : entry)),
      required: [...required.filter((name) => name !== previous), ...(mandatory ? [key] : [])]});
  };
  return <section className={form.stack}>
    <div className={form.heading}><strong>{text("输入字段")}</strong></div>
    {Object.entries(properties).map(([key, raw], index) => {
      const field = record(raw);
      return <div className={form.row} key={index}>
        <div className={form.inline}><Input aria-label={text("显示名称")} placeholder={text("显示名称")} value={String(field.title ?? key)} maxLength={100}
          onChange={(event) => change(index, key, {...field, title: event.target.value}, required.includes(key))}/>
          <Button type="button" className={form.textAction} aria-label={text("删除输入字段 {0}", [String(field.title || key)])} onClick={() => onChange({
            ...value, properties: Object.fromEntries(Object.entries(properties).filter(([name]) => name !== key)), required: required.filter((name) => name !== key)
          })}>{text("删除")}</Button></div>
        <div className={form.inline}><Select aria-label={text("数据类型")} value={String(field.type ?? "string")} onChange={(event) => {
          const type = event.target.value;
          change(index, key, {...field, type, ...(type === "object" ? {properties: field.properties ?? {}} : type === "array" ? {items: field.items ?? {type: "string"}} : {})}, required.includes(key));
        }}><option value="string">{text("文字")}</option><option value="number">{text("数字")}</option><option value="integer">{text("整数")}</option>
          <option value="boolean">{text("是或否")}</option><option value="object">{text("对象")}</option><option value="array">{text("列表")}</option></Select>
          <label className={ui.check}><Checkbox checked={required.includes(key)} onCheckedChange={(checked) => change(index, key, field, checked)}/>{text("必填")}</label></div>
        <WorkflowAdvanced title={text("字段标识与说明")}><div className={form.stack}>
          <label className={ui.field}><span>{text("字段标识")}</span><Input value={key} required maxLength={128} pattern="[A-Za-z_][A-Za-z0-9_-]*" onChange={(event) => {
            const name = event.target.value;
            if (name !== key && Object.hasOwn(properties, name)) {
              event.target.setCustomValidity(text("字段名称不能重复。"));
              event.target.reportValidity();
              return;
            }
            event.target.setCustomValidity("");
            change(index, name, field, required.includes(key));
          }}/></label>
          <label className={ui.field}><span>{text("说明")}</span><Input value={String(field.description ?? "")} maxLength={500}
            onChange={(event) => change(index, key, {...field, description: event.target.value}, required.includes(key))}/></label>
          {["object", "array"].includes(String(field.type)) && <JsonField label={text("字段结构（JSON Schema）")} value={field} object
            onChange={(next) => change(index, key, next as Record<string, unknown>, required.includes(key))}/>}
        </div></WorkflowAdvanced>
      </div>;
    })}
    <Button type="button" className={form.textAction} disabled={Object.keys(properties).length >= 100} onClick={() => {
      const key = uniqueParameterName(properties, "input");
      onChange({...value, type: "object", properties: {...properties, [key]: {type: "string", title: text("新的输入")}}});
    }}>{text("添加输入字段")}</Button>
    <WorkflowAdvanced title={text("完整输入结构")}><JsonField label={text("输入结构（JSON Schema）")} value={value} object
      onChange={(next) => onChange(next as Record<string, unknown>)} rows={8}/></WorkflowAdvanced>
  </section>;
}
