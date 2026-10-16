"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";
import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import type {Comparison, Condition} from "../types/workflow";
import {VariableField, type VariableOption} from "./workflow-value-fields";
import {WorkflowValueEditor} from "./workflow-value-editor";
import {parameterType} from "../lib/workflow-parameters";
import ui from "@/components/ui/surface.module.css";
import form from "./workflow-parameters.module.css";

const operators: Record<Comparison["operator"], string> = {
  eq: "等于", ne: "不等于", gt: "大于", gte: "大于或等于", lt: "小于", lte: "小于或等于",
  in: "在给定列表中", contains: "包含", exists: "字段存在"
};

export function WorkflowConditionEditor({value, onChange, variables, depth = 1}: {
  value: Condition; onChange: (value: Condition) => void; variables: VariableOption[]; depth?: number;
}) {
  const text = useT();
  const fresh = (): Condition => ({field: variables.find((item) => item.path.startsWith("input."))?.path ?? "input", operator: "exists"});
  const kind = "field" in value ? "comparison" : "all" in value ? "all" : "any" in value ? "any" : "not";
  const children = "all" in value ? value.all : "any" in value ? value.any : "not" in value ? [value.not] : [];
  const changeChildren = (next: Condition[]) => onChange(kind === "all" ? {all: next} : kind === "any" ? {any: next} : {not: next[0]});
  const source = "field" in value ? variables.find((item) => item.path === value.field)?.schema : undefined;
  const type = source ? parameterType(source) : "";
  return <div className={form.stack}>
    {"field" in value ? <>
      <VariableField label={text("比较字段")} value={value.field} onChange={(field) => onChange({...value, field})} variables={variables} maximum={200}/>
      <label className={ui.field}><span>{text("比较方式")}</span><Select value={value.operator} onChange={(event) => {
        const operator = event.target.value as Comparison["operator"];
        onChange(operator === "exists" ? {field: value.field, operator} : {...value, operator,
          value: operator === "in" ? Array.isArray(value.value) ? value.value : [] : ["gt", "gte", "lt", "lte"].includes(operator) ? typeof value.value === "number" ? value.value : 0 : value.value ?? ""});
      }}>{Object.entries(localizeCatalog(operators, text)).map(([operator, label]) => <option key={operator} value={operator}>{label}</option>)}</Select></label>
      {value.operator !== "exists" && <WorkflowValueEditor label={text("比较值")} value={value.value} variables={variables}
        schema={value.operator === "in" ? {type: "array"} : ["gt", "gte", "lt", "lte"].includes(value.operator) ? {type: "number"} : type && type !== "object" && type !== "array" ? {type} : {}}
        onChange={(next) => onChange({...value, value: next})}/>}
      {depth < 5 && <Select aria-label={text("组合条件")} value="" onChange={(event) => {
        if (event.target.value === "all") {
          onChange({all: [value, fresh()]});
        } else if (event.target.value === "any") {
          onChange({any: [value, fresh()]});
        } else if (event.target.value === "not") {
          onChange({not: value});
        }
      }}><option value="">{text("添加组合条件")}</option><option value="all">{text("同时满足另一个条件")}</option>
        <option value="any">{text("或满足另一个条件")}</option><option value="not">{text("条件不成立")}</option></Select>}
    </> : <>
      <Select aria-label={text("判断方式")} value={kind} onChange={(event) => {
        const next = event.target.value;
        onChange(next === "comparison" ? children[0] ?? fresh() : next === "all" ? {all: children} : next === "any" ? {any: children} : {not: children[0]});
      }}><option value="all">{text("全部条件成立")}</option><option value="any">{text("任一条件成立")}</option>
        <option value="not">{text("条件不成立")}</option><option value="comparison">{text("保留第一个条件")}</option></Select>
      <div className={form.nested}>{children.map((child, index) => <div className={form.row} key={index}>
        <WorkflowConditionEditor value={child} variables={variables} depth={depth + 1} onChange={(next) => changeChildren(children.map((item, offset) => offset === index ? next : item))}/>
        {children.length > 1 && <Button type="button" className={form.textAction} onClick={() => changeChildren(children.filter((_, offset) => offset !== index))}>{text("移除此条件")}</Button>}
      </div>)}{kind !== "not" && <Button type="button" className={form.textAction} disabled={children.length >= 20}
        onClick={() => changeChildren([...children, fresh()])}>{text("添加条件")}</Button>}</div>
    </>}
  </div>;
}
