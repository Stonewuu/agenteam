"use client";

import {useId, useRef} from "react";
import {useT} from "@/lib/i18n/locale-provider";
import {Input} from "./input";
import {Switch} from "./shadcn/switch";
import {FieldErrorFeedback} from "./error-feedback";
import {useControlDisabled} from "./fieldset";
import styles from "./limit-field.module.css";
import ui from "./surface.module.css";

export const UNLIMITED_VALUE = -1;

/** 切换不限时保留正在编辑的数值，关闭不限后继续编辑原值。 */
export function LimitField({label, name, value, minimum = 1, restoreValue, onChange, disabled, hint, error}: {
  label: string;
  name: string;
  value: string | number;
  minimum?: number;
  restoreValue?: string | number;
  onChange: (value: string) => void;
  disabled?: boolean;
  hint?: string;
  error?: string;
}) {
  const t = useT();
  const id = useId();
  const unavailable = useControlDisabled(disabled);
  const unlimited = String(value) === String(UNLIMITED_VALUE);
  const previous = useRef(unlimited ? String(minimum) : String(value));
  const input = useRef<HTMLInputElement>(null);
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return <div className={styles.field}>
    <div className={styles.heading}>
      <label className={ui.fieldLabel} htmlFor={id}>{label}</label>
      <label className={styles.toggle}>
        <span>{t("不限")}</span>
        <Switch checked={unlimited} disabled={unavailable} aria-label={t("{0}不限", [label])}
          onCheckedChange={checked => {
            if (checked) {
              previous.current = String(value).trim() && Number.isFinite(Number(value)) ? String(value) : String(minimum);
              onChange(String(UNLIMITED_VALUE));
            } else {
              onChange(restoreValue === undefined ? previous.current : String(restoreValue));
              requestAnimationFrame(() => input.current?.focus());
            }
          }}/>
      </label>
    </div>
    <Input ref={input} id={id} name={name} className={ui.input} type="number" min={minimum} step={1}
      value={unlimited || !Number.isFinite(Number(value)) ? "" : value} placeholder={unlimited ? t("不限") : undefined}
      required={!unlimited} disabled={unavailable || unlimited} aria-invalid={Boolean(error)} aria-describedby={describedBy}
      onChange={event => onChange(event.target.value)}/>
    <div className={styles.feedback}>
      {error ? <FieldErrorFeedback id={`${id}-error`} messages={[error]}/>
        : hint ? <p id={`${id}-hint`} className="field-hint">{hint}</p> : null}
    </div>
  </div>;
}
