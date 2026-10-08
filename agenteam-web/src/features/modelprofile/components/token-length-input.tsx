"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {formatTokenLength, parseTokenLength} from "../lib/model-token-length";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-profile-form.module.css";

export function TokenLengthInput({label, name, value, presets, error, onChange}: {
  label: string; name: string; value: string; presets: number[]; error?: string; onChange: (value: string) => void;
}) {
  const uiText = useT();
  const parsed = parseTokenLength(value);
  return <div className={styles.lengthField}>
    <Field label={label} required error={error} hint={parsed !== null && parsed >= 128
      ? `${parsed.toLocaleString("en-US")} Token` : uiText("选择常用值，也可输入准确长度")}>
      <Input className={ui.input} name={name} value={value} placeholder={uiText("例如 128K")} autoComplete="off"
             onChange={(event) => onChange(event.target.value)}/>
    </Field>
    <div className={styles.presets} role="group" aria-label={uiText("{0}常用值", [label])}>
      {presets.map((preset) => <Button type="button" key={preset} aria-pressed={parsed === preset}
                                       onClick={() => onChange(formatTokenLength(preset))}>{formatTokenLength(preset)}</Button>)}
    </div>
  </div>;
}
