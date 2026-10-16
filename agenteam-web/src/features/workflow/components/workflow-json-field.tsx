"use client";

import {useState} from "react";
import {Textarea} from "@/components/ui/textarea";
import {useT} from "@/lib/i18n/locale-provider";
import {localizeUiMessage} from "@/lib/i18n/ui-message";
import ui from "@/components/ui/surface.module.css";
import styles from "./workflow.module.css";

/** 格式错误保留在输入框并阻止提交，不用旧值冒充当前编辑内容。 */
export function JsonField({label, value, onChange, object = false, array = false, rows = 5}: {
  label: string;
  value: unknown;
  onChange: (value: unknown) => void;
  object?: boolean;
  array?: boolean;
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
      if (object && (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) || array && !Array.isArray(parsed)) {
        throw new Error();
      }
      event.target.setCustomValidity("");
      setLocal({saved: JSON.stringify(parsed, null, 2), text, error: ""});
      onChange(parsed);
    } catch {
      const error = array ? uiText("请填写有效的列表，用中括号包围条目，文字需加双引号。") : object ? uiText("请填写有效的 JSON 对象（用大括号组织字段和值）。") : uiText("请填写有效的 JSON 值（文字需加双引号）。");
      event.target.setCustomValidity(error);
      setLocal({saved: formatted, text, error});
    }
  }}/>{local.error && <span className={ui.error} role="alert">{localizeUiMessage(local.error ?? "", uiText)}</span>}
  </label>;
}
