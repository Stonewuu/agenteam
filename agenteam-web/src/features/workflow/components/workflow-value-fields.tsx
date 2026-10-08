"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Textarea} from "@/components/ui/textarea";
import {Input} from "@/components/ui/input";
import {Button} from "@/components/ui/button";

import {Select} from "@/components/ui/select";


import {useId, useState} from "react";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

export type VariableOption = { path: string; label: string };

/** 格式错误保留在输入框并阻止提交，不用旧值冒充当前编辑内容。 */
export function JsonField({label, value, onChange, object = false, rows = 5}: {
  label: string;
  value: unknown;
  onChange: (value: unknown) => void;
  object?: boolean;
  rows?: number
}) {
  const uiText = useT();
  const formatted = JSON.stringify(value ?? null, null, 2);
  const [local, setLocal] = useState({saved: formatted, text: formatted, error: ""});
  if (local.saved !== formatted) {
    setLocal({saved: formatted, text: formatted, error: ""});
  }
  return <label className={ui.field}><span>{label}</span><Textarea data-workflow-json
                                                                   className={`${ui.input} ${styles.code}`}
                                                                   value={local.text} rows={rows} spellCheck={false}
                                                                   aria-invalid={Boolean(local.error)}
                                                                   ref={(element) => {
                                                                     element?.setCustomValidity(local.error);
                                                                   }} onChange={(event) => {
    const text = event.target.value;
    try {
      const parsed: unknown = JSON.parse(text);
      if (object && (!parsed || typeof parsed !== "object" || Array.isArray(parsed))) {
        throw new Error();
      }
      event.target.setCustomValidity("");
      setLocal({saved: JSON.stringify(parsed, null, 2), text, error: ""});
      onChange(parsed);
    } catch {
      const error = object ? uiText("请填写有效的 JSON 对象（用大括号组织字段和值）。") : uiText("请填写有效的 JSON 值（文字需加双引号）。");
      event.target.setCustomValidity(error);
      setLocal({saved: formatted, text, error});
    }
  }}/>{local.error && <span className={ui.error} role="alert">{localizeUiMessage(local.error ?? "", uiText)}</span>}
  </label>;
}

export function VariableField({
                                label,
                                value,
                                onChange,
                                variables,
                                template = false,
                                multiline = false,
                                maximum = 20000
                              }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  variables: VariableOption[];
  template?: boolean;
  multiline?: boolean;
  maximum?: number;
}) {
  const uiText = useT();
  const id = useId();
  const props = {
    id,
    className: ui.input,
    value,
    maxLength: maximum,
    onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(event.target.value)
  };
  return <div className={styles.variable}><label className={ui.field} htmlFor={id}><span>{label}</span>{multiline ?
    <Textarea {...props} rows={3}/> : <Input {...props} />}</label>
    <Select className={ui.select} aria-label={uiText("为{0}选择变量", [label])} value="" onChange={(event) => {
      if (event.target.value) {
        onChange(template ? `${value}\${${event.target.value}}` : event.target.value);
      }
    }}>
      <option value="">{template ? uiText("插入输入或步骤结果…") : uiText("选择输入或步骤结果…")}</option>
      {variables.map((variable) => <option value={variable.path} key={variable.path}>{variable.label}</option>)}
    </Select>
  </div>;
}

export function MappingEditor({label, value, onChange, variables}: {
  label: string;
  value: Record<string, unknown>;
  onChange: (value: Record<string, unknown>) => void;
  variables: VariableOption[]
}) {
  const uiText = useT();
  const [source, setSource] = useState(false);
  const change = (index: number, key: string, content: unknown) => onChange(Object.fromEntries(Object.entries(value).map((entry, offset) => offset === index ? [key, content] : entry)));
  return <section className={styles.section}>
    <div className={styles.heading}><h4>{label}</h4><Button className={ui.button} type="button"
                                                            onClick={() => setSource((value) => !value)}>{source ? uiText("按字段编辑") : uiText("编辑完整对象")}</Button>
    </div>
    {source ? <JsonField label={uiText("{0}（JSON 对象）", [label])} value={value}
                         onChange={(next) => onChange(next as Record<string, unknown>)} object/> :
      <div className={styles.mapping}>
        {Object.entries(value).map(([key, content], index) => <div className={styles.mappingRow} key={index}>
          <div className={styles.line}><label className={ui.field}><span>{uiText("字段名称")}</span><Input
            className={ui.input} value={key} maxLength={128} required onChange={(event) => {
            const name = event.target.value;
            if (name !== key && Object.hasOwn(value, name)) {
              event.target.setCustomValidity(uiText("字段名称不能重复。"));
              event.target.reportValidity();
              return;
            }
            event.target.setCustomValidity("");
            change(index, name, content);
          }}/></label><Button type="button" className={ui.danger}
                              aria-label={uiText("删除字段 {0}", [key || index + 1])}
                              onClick={() => onChange(Object.fromEntries(Object.entries(value).filter((_, offset) => offset !== index)))}>{uiText("删除")}</Button>
          </div>
          <label className={ui.field}><span>{uiText("内容类型")}</span><Select className={ui.select}
                                                                               value={typeof content === "string" ? "text" : "json"}
                                                                               onChange={(event) => change(index, key, event.target.value === "text" ? "" : null)}>
            <option value="text">{uiText("文字或变量")}</option>
            <option value="json">{uiText("数字、对象或数组")}</option>
          </Select></label>
          {typeof content === "string" ?
            <VariableField label={uiText("内容")} value={content} onChange={(next) => change(index, key, next)}
                           variables={variables} template multiline/>
            : <JsonField label={uiText("字段值")} value={content} onChange={(next) => change(index, key, next)}
                         rows={3}/>}
        </div>)}
        <Button type="button" className={ui.button} disabled={Object.keys(value).length >= 100} onClick={() => {
          let index = 1;
          while (Object.hasOwn(value, `field${index}`)) {
            index++;
          }
          onChange({...value, [`field${index}`]: ""});
        }}>{uiText("添加字段")}</Button>
      </div>}
  </section>;
}
