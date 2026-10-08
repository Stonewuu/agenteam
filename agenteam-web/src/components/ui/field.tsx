"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {createContext, type ReactNode, useContext, useId, useState,} from "react";
import {FieldErrorFeedback} from "./error-feedback";

const FieldContext = createContext<{
  labelId: string;
  describedBy?: string;
  invalid: boolean;
} | null>(null);

export function useFieldContext() {
  return useContext(FieldContext);
}

export function Field({
                        label,
                        children,
                        hint,
                        error,
                        required,
                      }: {
  label: string;
  children: ReactNode;
  hint?: string;
  error?: string;
  required?: boolean;
}) {
  const uiText = useT();
  const id = useId();
  const [validationError, setValidationError] = useState("");
  const visibleError = error || validationError;
  const describedBy =
    [hint && `${id}-hint`, visibleError && `${id}-error`]
      .filter(Boolean)
      .join(" ") || undefined;

  return (
    <FieldContext.Provider
      value={{
        labelId: `${id}-label`,
        describedBy,
        invalid: Boolean(visibleError),
      }}
    >
      <div
        className={`field ${visibleError ? "has-error" : ""}`}
        onInvalid={(event) => {
          event.preventDefault();
          const input = event.target as
            HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;
          const message = input.validity.valueMissing
            ? uiText(input.tagName === "SELECT" ? "请选择{0}。" : "请填写{0}。", [label])
            : input.validity.typeMismatch
              ? uiText("请填写有效的{0}。", [label])
              : input.validity.tooShort
                ? uiText("{0}的内容过短，请按要求填写。", [label])
                : input.validity.rangeOverflow || input.validity.rangeUnderflow
                  ? uiText("{0}超出允许范围。", [label])
                  : uiText("请检查{0}的内容。", [label]);
          setValidationError(message);
          input.setAttribute("aria-invalid", "true");
          input.setAttribute("aria-describedby", `${id}-error`);
          input.form
            ?.querySelector<HTMLElement>(
              "input:invalid, textarea:invalid, select:invalid",
            )
            ?.focus();
        }}
        onInput={(event) => {
          setValidationError("");
          const input = event.target as HTMLElement;
          if (!error) {
            input.removeAttribute("aria-invalid");
            const remaining = input.getAttribute("aria-describedby")?.split(/\s+/).filter((value) => value !== `${id}-error`).join(" ");
            if (remaining) {
              input.setAttribute("aria-describedby", remaining);
            } else {
              input.removeAttribute("aria-describedby");
            }
          }
        }}
      >
        <label id={id}>
          <span id={`${id}-label`}>
            {label}
            {required && <span className="required"> *</span>}
          </span>
          {children}
        </label>
        {hint && (
          <p id={`${id}-hint`} className="field-hint">
            {hint}
          </p>
        )}
        <FieldErrorFeedback id={`${id}-error`} messages={visibleError ? [visibleError] : []}/>
      </div>
    </FieldContext.Provider>
  );
}
