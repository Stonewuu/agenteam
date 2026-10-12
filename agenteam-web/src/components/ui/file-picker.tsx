"use client";

import {type ComponentProps, type Ref, useEffect, useImperativeHandle, useRef, useState} from "react";
import {Input} from "./shadcn/input";
import {Button} from "./button";
import {useFieldContext} from "./field";
import {useControlDisabled} from "./fieldset";
import {useNativeFieldError} from "./use-native-field-error";
import {FieldErrorFeedback} from "./error-feedback";
import {IconUpload} from "./icons";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "./surface.module.css";
import styles from "./file-picker.module.css";

export function FilePicker({ref, triggerRef, selectedName, label, disabled, className, onChange, onInvalid, onInput, onFocus, ...props}:
  Omit<ComponentProps<"input">, "type" | "hidden" | "value" | "defaultValue"> & {
    selectedName?: string; label?: string; triggerRef?: Ref<HTMLButtonElement>;
  }) {
  const t = useT();
  const field = useFieldContext();
  const input = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const [localName, setLocalName] = useState("");
  const inactive = useControlDisabled(disabled);
  const validation = useNativeFieldError<HTMLInputElement>({...props, onInvalid, onInput});
  const clearError = validation.clearError;
  useImperativeHandle(ref, () => input.current!, []);
  useImperativeHandle(triggerRef, () => trigger.current!, []);
  useEffect(() => {
    const form = input.current?.form;
    const reset = () => {
      setLocalName("");
      clearError();
    };
    form?.addEventListener("reset", reset);
    return () => form?.removeEventListener("reset", reset);
  }, [clearError]);
  return <><span className={`${styles.control} ${className ?? ""}`}>
    <Input {...props} {...validation.control} ref={input} type="file" hidden tabIndex={-1} aria-hidden="true" disabled={inactive}
      onFocus={(event) => {
        trigger.current?.focus();
        onFocus?.(event);
      }}
      onInvalid={(event) => {
        validation.control.onInvalid(event);
        event.preventDefault();
        const first = event.currentTarget.form?.querySelector("input:invalid, select:invalid, textarea:invalid");
        if (!first || first === event.currentTarget) {
          trigger.current?.focus();
        }
      }}
      onChange={(event) => {
        setLocalName(Array.from(event.target.files ?? []).map((file) => file.name).join("、"));
        clearError();
        onChange?.(event);
      }}/>
    <Button ref={trigger} type="button" className={ui.button} disabled={inactive}
      aria-invalid={validation.control["aria-invalid"]}
      aria-describedby={validation.control["aria-describedby"]}
      aria-required={props.required || props["aria-required"]}
      onClick={() => input.current?.click()}><IconUpload size={17}/>{label ?? t("选择文件")}</Button>
    <span className={styles.name}>{(selectedName ?? localName) || t("尚未选择文件")}</span>
  </span><FieldErrorFeedback id={validation.errorId} messages={!field?.invalid && validation.error ? [validation.error] : []}/></>;
}
