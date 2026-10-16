"use client";

import {type ReactNode, useId, useRef, useState} from "react";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";
import {Select} from "@/components/ui/select";
import {Button} from "@/components/ui/button";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {useControlDisabled} from "@/components/ui/fieldset";
import {IconChevronDown} from "@/components/ui/icons";
import {useT} from "@/lib/i18n/locale-provider";
import {formatPayloadScalar} from "@/features/plugin/lib/tool-payload-format";
import {parameterChoices, parameterDefault, parameterType, referencePath, referenceValue, type ParameterSchema, type VariableOption} from "../lib/workflow-parameters";
import {JsonField} from "./workflow-json-field";
import {WorkflowVariablePicker} from "./workflow-variable-picker";
import form from "./workflow-parameters.module.css";

export function WorkflowAdvanced({title, children, initiallyOpen = false}: {title: string; children: ReactNode; initiallyOpen?: boolean}) {
  const readOnly = useControlDisabled();
  const [open, setOpen] = useState(initiallyOpen || readOnly), id = useId();
  const container = useRef<HTMLElement>(null);
  return <section className={form.advanced} ref={container}>
    <Button type="button" className={form.toggle} aria-expanded={open} aria-controls={id} onClick={() => {
      if (open && !Array.from(container.current?.querySelectorAll<HTMLTextAreaElement>("textarea[data-workflow-json]") ?? []).every((input) => input.reportValidity())) {
        return;
      }
      setOpen(!open);
    }}>
      {title}<IconChevronDown size={15}/>
    </Button>
    <AnimatedHeight preserveControlShadows><div id={id}>{open && <div className={form.advancedBody}>{children}</div>}</div></AnimatedHeight>
  </section>;
}

/** 直接填写与引用共用同一个值，不另存一套界面专用参数。 */
export function WorkflowValueEditor({label, value, onChange, variables, schema = {}, required = false, multiline = false,
  children, allowReferences = true, fieldKey = ""}: {
  label: string; value: unknown; onChange: (value: unknown) => void; variables: VariableOption[];
  schema?: ParameterSchema; required?: boolean; multiline?: boolean; children?: ReactNode; allowReferences?: boolean; fieldKey?: string;
}) {
  const text = useT(), id = useId(), container = useRef<HTMLDivElement>(null);
  const [choosing, setChoosing] = useState(false);
  const path = referencePath(value), reference = allowReferences && (Boolean(path) || choosing);
  const definedType = parameterType(schema), choices = parameterChoices(schema);
  const [chosenType, setChosenType] = useState("");
  const type = definedType || chosenType || (Array.isArray(value) ? "array" : value !== null && typeof value === "object" ? "object" : typeof value === "number" ? "number" : typeof value === "boolean" ? "boolean" : "string");
  const knownVariable = variables.find((item) => item.path === path);
  const actualType = knownVariable?.schema ? parameterType(knownVariable.schema) : "";
  const mismatch = definedType && actualType && definedType !== actualType && !(definedType === "number" && actualType === "integer");
  const direct = () => {
    setChoosing(false);
    onChange(parameterDefault(schema) ?? (type === "object" ? {} : type === "array" ? [] : type === "boolean" ? false : type === "number" || type === "integer" ? undefined : ""));
  };
  let editor: ReactNode;
  if (reference) {
    editor = <><WorkflowVariablePicker label={text("为{0}选择变量", [label])} value={path} variables={variables} onChange={(next) => {
      setChoosing(false);
      onChange(referenceValue(next));
    }}/>{mismatch && <p className={form.error}>{text("所选数据类型与参数要求不同，请检查来源。")}</p>}</>;
  } else if (children) {
    editor = children;
  } else if (choices.length || type === "boolean") {
    const options = choices.length ? choices : [{value: true, label: text("是")}, {value: false, label: text("否")}];
    const selected = value === undefined ? "" : JSON.stringify(value);
    editor = <Select id={id} aria-label={label} value={selected} required={required} onChange={(event) => onChange(event.target.value ? JSON.parse(event.target.value) : undefined)}>
      <option value="">{text("请选择")}</option>
      {selected && !options.some((option) => JSON.stringify(option.value) === selected) && <option value={selected}>{String(value)}</option>}
      {options.map((option) => <option key={JSON.stringify(option.value)} value={JSON.stringify(option.value)}>
        {option.label === String(option.value) ? formatPayloadScalar(option.value, fieldKey, undefined, text) : option.label}</option>)}
    </Select>;
  } else if ((type === "number" || type === "integer") && (value === undefined || typeof value === "number")) {
    editor = <Input id={id} aria-label={label} type="number" value={typeof value === "number" ? value : ""} required={required}
      min={typeof schema.minimum === "number" ? schema.minimum : undefined} max={typeof schema.maximum === "number" ? schema.maximum : undefined}
      step={typeof schema.multipleOf === "number" ? schema.multipleOf : type === "integer" ? 1 : "any"}
      onChange={(event) => onChange(event.target.value === "" ? undefined : Number(event.target.value))}/>;
  } else if (type === "object" || type === "array" || value === null || ["number", "integer"].includes(type)) {
    editor = <JsonField label={text("内容")} value={value === undefined ? type === "array" ? [] : {} : value} object={type === "object"} array={type === "array"}
      onChange={onChange} rows={3}/>;
  } else {
    const props = {id, value: String(value ?? ""), required, maxLength: typeof schema.maxLength === "number" ? schema.maxLength : 20000,
      minLength: typeof schema.minLength === "number" ? schema.minLength : undefined,
      placeholder: typeof schema.default === "string" ? schema.default : undefined,
      onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(event.target.value)};
    editor = <>{multiline ? <Textarea {...props} rows={3}/> : <Input {...props}/>}
      {allowReferences && <WorkflowVariablePicker compact label={text("插入变量")} variables={variables}
        onChange={(next) => onChange(`${String(value ?? "")}${referenceValue(next)}`)}/>}</>;
  }
  return <div className={form.stack} ref={container}>
    <div className={form.heading}><label htmlFor={id}>{label}{required && <small>{text("必填")}</small>}</label>
      {allowReferences && <Button type="button" className={form.textAction} onClick={() => {
        if (Array.from(container.current?.querySelectorAll<HTMLTextAreaElement>("textarea[data-workflow-json]") ?? []).every((input) => input.reportValidity())) {
          if (reference) {
            direct();
          } else {
            setChoosing(true);
          }
        }
      }}>
        {reference ? text("直接填写") : text("引用变量")}</Button>}
    </div>
    {typeof schema.description === "string" && schema.description && <p className={form.description}>{schema.description}</p>}
    {!reference && !definedType && !choices.length && !children && <Select aria-label={text("{0}的数据类型", [label])} value={value === null ? "null" : type}
      onChange={(event) => {
        const next = event.target.value;
        setChosenType(next);
        onChange(next === "number" ? 0 : next === "boolean" ? false : next === "object" ? {} : next === "array" ? [] : next === "null" ? null : "");
      }}>
      <option value="string">{text("文字")}</option><option value="number">{text("数字")}</option><option value="boolean">{text("是或否")}</option>
      <option value="object">{text("对象")}</option><option value="array">{text("列表")}</option><option value="null">{text("空值")}</option>
    </Select>}
    {editor}
  </div>;
}
