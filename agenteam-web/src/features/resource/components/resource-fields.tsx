"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Textarea} from "@/components/ui/textarea";
import {Input} from "@/components/ui/input";
import {Fieldset} from "@/components/ui/fieldset";
import {Button} from "@/components/ui/button";
import {FieldErrorFeedback} from "@/components/ui/error-feedback";

import {type ReactNode, useId} from "react";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";

export type FieldErrors = Record<string, string[]>;

export function TextField({
                            label,
                            value,
                            onChange,
                            maximum,
                            required = false,
                            multiline = false,
                            name,
                            errors = {},
                            hint
                          }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  maximum: number;
  required?: boolean;
  multiline?: boolean;
  name: string;
  errors?: FieldErrors;
  hint?: string;
}) {
  const uiText = useT();
  const id = useId();
  const describedBy = [hint && `${id}-hint`, errors[name]?.length && `${id}-error`].filter(Boolean).join(" ") || undefined;
  const common = {
    id,
    name,
    value,
    maxLength: maximum,
    required,
    "aria-invalid": Boolean(errors[name]?.length),
    "aria-describedby": describedBy,
    onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(event.target.value)
  };
  return <label className={ui.field} htmlFor={id}><span>{label}{required ? uiText("（必填）") : ""}</span>
    {multiline ? <Textarea className={ui.textarea} {...common} /> : <Input className={ui.input} {...common} />}
    {hint && <small id={`${id}-hint`} className={ui.description}>{hint}</small>}
    <FieldErrorFeedback id={`${id}-error`} messages={errors[name]}/>
  </label>;
}

export function NumberField({label, value, min, max, step = 1, onChange, name, errors = {}, hint}: {
  label: string;
  value: number;
  min: number;
  max?: number;
  step?: number;
  onChange: (value: number) => void;
  name: string;
  errors?: FieldErrors;
  hint?: string
}) {
  const id = useId();
  const describedBy = [hint && `${id}-hint`, errors[name]?.length && `${id}-error`].filter(Boolean).join(" ") || undefined;
  return <label className={ui.field} htmlFor={id}><span>{label}</span><Input id={id} className={ui.input} type="number"
                                                                             name={name} min={min} max={max} step={step}
                                                                             value={Number.isFinite(value) ? value : ""}
                                                                             required
                                                                             aria-invalid={Boolean(errors[name]?.length)}
                                                                             aria-describedby={describedBy}
                                                                             onChange={(event) => onChange(event.target.valueAsNumber)}/>
    {hint && <small id={`${id}-hint`} className={ui.description}>{hint}</small>}<FieldErrorFeedback id={`${id}-error`}
                                                                                                    messages={errors[name]}/></label>;
}

export function StringListField({label, values, onChange, maximum, length, name, errors = {}}: {
  label: string;
  values: string[];
  onChange: (values: string[]) => void;
  maximum: number;
  length: number;
  name: string;
  errors?: FieldErrors
}) {
  const uiText = useT();
  return <Fieldset className={styles.section}>
    <legend>{label}</legend>
    <div className={ui.form}>
      {values.map((value, index) => <div className={styles.line} key={index}><TextField label={`${label} ${index + 1}`}
                                                                                        name={`${name}.${index}`}
                                                                                        value={value} maximum={length}
                                                                                        required
                                                                                        onChange={(value) => onChange(values.map((existing, at) => at === index ? value : existing))}
                                                                                        errors={errors}/>
        <Button className={ui.button} type="button" aria-label={uiText("移除{0} {1}", [label, index + 1])}
                onClick={() => onChange(values.filter((_, at) => at !== index))}>{uiText("移除")}</Button></div>)}
      <div><Button className={ui.button} type="button" disabled={values.length >= maximum}
                   onClick={() => onChange([...values, ""])}>{uiText("添加")}{label}</Button></div>
      <FieldErrorFeedback messages={errors[name]}/>
    </div>
  </Fieldset>;
}

export function Section({title, children}: { title?: string; children: ReactNode }) {
  if (!title) {
    return <div className={ui.form}>{children}</div>;
  }
  return <section className={styles.section}><h2>{title}</h2>
    <div className={ui.form}>{children}</div>
  </section>;
}
