"use client";

import {useT} from "@/lib/i18n/locale-provider";
import {localizeCatalog} from "@/lib/i18n/translate";

import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import type {Comparison, Condition} from "../types/workflow";
import {JsonField, VariableField, type VariableOption} from "./workflow-value-fields";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

const fresh = (): Condition => ({field: "input.text", operator: "exists"});
const operators: Record<Comparison["operator"], string> = {
  eq: "等于",
  ne: "不等于",
  gt: "大于",
  gte: "大于或等于",
  lt: "小于",
  lte: "小于或等于",
  in: "在给定列表中",
  contains: "包含",
  exists: "字段存在"
};

export function WorkflowConditionEditor({value, onChange, variables, depth = 1}: {
  value: Condition;
  onChange: (value: Condition) => void;
  variables: VariableOption[];
  depth?: number
}) {
  const uiText = useT();
  const kind = "field" in value ? "comparison" : "all" in value ? "all" : "any" in value ? "any" : "not";
  const children = "all" in value ? value.all : "any" in value ? value.any : "not" in value ? [value.not] : [];
  const changeChildren = (next: Condition[]) => onChange(kind === "all" ? {all: next} : kind === "any" ? {any: next} : {not: next[0]});
  return <div className={styles.condition}>
    <label className={ui.field}><span>{uiText("判断方式")}</span><Select className={ui.select} value={kind}
                                                                         onChange={(event) => {
                                                                           onChange(event.target.value === "comparison" ? fresh() : event.target.value === "all" ? {all: [value]} : event.target.value === "any" ? {any: [value]} : {not: value});
                                                                         }}>
      <option value="comparison">{uiText("比较字段")}</option>
      <option value="all" disabled={depth >= 5}>{uiText("全部条件成立")}</option>
      <option value="any" disabled={depth >= 5}>{uiText("任一条件成立")}</option>
      <option value="not" disabled={depth >= 5}>{uiText("条件不成立")}</option>
    </Select></label>
    {"field" in value ? <>
        <VariableField label={uiText("比较字段")} value={value.field} onChange={(field) => onChange({...value, field})}
                       variables={variables} maximum={200}/>
        <label className={ui.field}><span>{uiText("比较方式")}</span><Select className={ui.select} value={value.operator}
                                                                             onChange={(event) => {
                                                                               const operator = event.target.value as Comparison["operator"];
                                                                               onChange(operator === "exists" ? {
                                                                                 field: value.field,
                                                                                 operator
                                                                               } : {
                                                                                 ...value,
                                                                                 operator,
                                                                                 value: value.value ?? ""
                                                                               });
                                                                             }}>
          {Object.entries(localizeCatalog(operators, uiText)).map(([operator, label]) => <option key={operator}
                                                                                                 value={operator}>{label}</option>)}
        </Select></label>
        {value.operator !== "exists" && <>
          <label className={ui.field}><span>{uiText("比较值类型")}</span><Select className={ui.select}
                                                                                 value={typeof value.value === "string" ? "text" : "json"}
                                                                                 onChange={(event) => onChange({
                                                                                   ...value,
                                                                                   value: event.target.value === "text" ? "" : null
                                                                                 })}>
            <option value="text">{uiText("文字或变量")}</option>
            <option value="json">{uiText("数字、布尔值或列表")}</option>
          </Select></label>
          {typeof value.value === "string" ? <VariableField label={uiText("比较值")} value={value.value}
                                                            onChange={(next) => onChange({...value, value: next})}
                                                            variables={variables} template/> :
            <JsonField label={uiText("比较值")} value={value.value} onChange={(next) => onChange({...value, value: next})}
                       rows={2}/>}
        </>}
      </> :
      <div className={styles.conditionGroup}>{children.map((child, index) => <div className={styles.fields} key={index}>
        <WorkflowConditionEditor value={child} variables={variables} depth={depth + 1}
                                 onChange={(next) => changeChildren(children.map((item, offset) => offset === index ? next : item))}/>
        {children.length > 1 && <Button type="button" className={ui.danger}
                                        onClick={() => changeChildren(children.filter((_, offset) => offset !== index))}>{uiText("移除此条件")}</Button>}
      </div>)}{kind !== "not" && <Button type="button" className={ui.button} disabled={children.length >= 50}
                                         onClick={() => changeChildren([...children, fresh()])}>{uiText("添加条件")}</Button>}</div>}
  </div>;
}
