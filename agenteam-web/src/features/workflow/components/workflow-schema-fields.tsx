"use client";

import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import {useT} from "@/lib/i18n/locale-provider";
import {payloadFieldLabel} from "@/features/plugin/lib/tool-payload-format";
import {parameterDefault, parameterObject, parameterSchema, parameterType, referencePath, type ParameterSchema, type VariableOption} from "../lib/workflow-parameters";
import {WorkflowAdvanced, WorkflowValueEditor} from "./workflow-value-editor";
import {JsonField} from "./workflow-json-field";
import {MappingEditor} from "./workflow-value-fields";
import form from "./workflow-parameters.module.css";

export function WorkflowSchemaFields({schema: raw, value, variables, onChange, root = raw, depth = 0}: {
  schema: ParameterSchema; root?: ParameterSchema; value: Record<string, unknown>; variables: VariableOption[];
  onChange: (value: Record<string, unknown>) => void; depth?: number;
}) {
  const text = useT();
  const schema = parameterSchema(raw, root), properties = parameterObject(schema.properties);
  const required = Array.isArray(schema.required) ? schema.required as string[] : [];
  const visible = Object.keys(properties).filter((key) => required.includes(key) || Object.hasOwn(value, key));
  const optional = Object.keys(properties).filter((key) => !visible.includes(key));
  const unknown = Object.fromEntries(Object.entries(value).filter(([key]) => !Object.hasOwn(properties, key)));
  const update = (name: string, content: unknown) => {
    const next = {...value, [name]: content};
    if (content === undefined) {
      delete next[name];
    }
    onChange(next);
  };
  return <div className={form.stack}>
    {visible.map((name) => {
      const field = parameterSchema(properties[name], root), mandatory = required.includes(name);
      const label = typeof field.title === "string" && field.title ? field.title : payloadFieldLabel(name, text);
      return <div key={name} className={form.row}>
        <SchemaValue label={label} fieldKey={name} schema={field} root={root} value={value[name]} required={mandatory} variables={variables}
          depth={depth} onChange={(content) => update(name, content)}/>
        {!mandatory && <Button type="button" className={form.textAction} aria-label={text("移除参数 {0}", [label])}
          onClick={() => update(name, undefined)}>{text("移除参数")}</Button>}
      </div>;
    })}
    {optional.length > 0 && <Select aria-label={text("添加可选参数")} value="" onChange={(event) => {
      const name = event.target.value;
      if (name) {
        const field = parameterSchema(properties[name], root), type = parameterType(field);
        update(name, parameterDefault(field, root) ?? (type === "object" ? {} : type === "array" ? [] : type === "boolean" ? false : type === "number" || type === "integer" ? 0 : ""));
      }
    }}><option value="">{text("添加可选参数")}</option>{optional.map((name) => <option key={name} value={name}>{String(parameterSchema(properties[name], root).title || payloadFieldLabel(name, text))}</option>)}</Select>}
    {Object.keys(unknown).length > 0 && <div className={form.stack}>
      {schema.additionalProperties === false && <p className={form.error}>{text("以下参数不属于当前工具，请移除或更换工具。")}</p>}
      <MappingEditor label={text("其他参数")} value={unknown} variables={variables} onChange={(next) => onChange({
        ...Object.fromEntries(Object.entries(value).filter(([key]) => Object.hasOwn(properties, key))), ...next
      })}/>
    </div>}
    {!visible.length && !optional.length && !Object.keys(unknown).length && schema.additionalProperties === false && <p className={form.description}>{text("此工具无需参数")}</p>}
    {depth === 0 && <WorkflowAdvanced title={text("高级参数")}>
      <div className={form.stack}>
        {schema.additionalProperties !== false && !Object.keys(unknown).length && <MappingEditor label={text("补充字段")} value={{}} variables={variables}
          onChange={(next) => onChange({...value, ...next})}/>}
        <JsonField label={text("完整参数（JSON）")} value={value} object onChange={(next) => onChange(next as Record<string, unknown>)}/>
      </div>
    </WorkflowAdvanced>}
  </div>;
}

function SchemaValue({schema, root, depth, value, onChange, ...props}: {
  schema: ParameterSchema; root: ParameterSchema; depth: number; value: unknown; onChange: (value: unknown) => void;
  label: string; fieldKey: string; required: boolean; variables: VariableOption[];
}) {
  const text = useT(), type = parameterType(schema), path = referencePath(value);
  let children;
  if (!path && depth < 5 && type === "object" && schema.properties && (value === undefined || value !== null && typeof value === "object" && !Array.isArray(value))) {
    children = <div className={form.nested}><WorkflowSchemaFields schema={schema} root={root} value={parameterObject(value)}
      variables={props.variables} onChange={onChange} depth={depth + 1}/></div>;
  } else if (!path && depth < 5 && type === "array" && (value === undefined || Array.isArray(value))) {
    const items = Array.isArray(value) ? value : [], itemSchema = parameterSchema(schema.items, root);
    children = <div className={form.nested}>{items.map((item, index) => <div key={index} className={form.row}>
      <SchemaValue {...props} label={text("第 {0} 项", [index + 1])} schema={itemSchema} root={root} depth={depth + 1}
        value={item} onChange={(next) => onChange(items.map((entry, offset) => offset === index ? next : entry))}/>
      <Button type="button" className={form.textAction} onClick={() => onChange(items.filter((_, offset) => offset !== index))}>{text("删除条目")}</Button>
    </div>)}<Button type="button" className={form.textAction} disabled={items.length >= Math.min(100, typeof schema.maxItems === "number" ? schema.maxItems : 100)}
      onClick={() => onChange([...items, parameterDefault(itemSchema, root) ?? (parameterType(itemSchema) === "object" ? {} : parameterType(itemSchema) === "array" ? [] : parameterType(itemSchema) === "boolean" ? false : ["number", "integer"].includes(parameterType(itemSchema)) ? 0 : "")])}>{text("添加条目")}</Button></div>;
  }
  return <WorkflowValueEditor {...props} schema={schema} value={value} onChange={onChange}>{children}</WorkflowValueEditor>;
}
