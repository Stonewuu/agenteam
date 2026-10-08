"use client";

import {type FormEventHandler, useCallback, useId, useState} from "react";
import {useFieldContext} from "./field";
import {useT} from "@/lib/i18n/locale-provider";
import type {Translator} from "@/lib/i18n/translate";

type Control = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;

export function useNativeFieldError<T extends Control>(props: {
  onInvalid?: FormEventHandler<T>; onInput?: FormEventHandler<T>;
  "aria-invalid"?: boolean | "true" | "false" | "grammar" | "spelling";
  "aria-describedby"?: string;
  "aria-label"?: string; "aria-labelledby"?: string;
}) {
  const t = useT();
  const errorId = useId();
  const field = useFieldContext();
  const [error, setError] = useState("");
  const [label, setLabel] = useState<string>();
  const clearError = useCallback(() => setError(""), []);
  const describedBy = [props["aria-describedby"] ?? field?.describedBy, error && errorId].filter(Boolean).join(" ") || undefined;
  const onInvalid: FormEventHandler<T> = (event) => {
    props.onInvalid?.(event);
    if (event.defaultPrevented || field) {
      return;
    }
    event.preventDefault();
    const input = event.currentTarget;
    const inputLabel = nativeLabel(input);
    if (inputLabel) {
      setLabel(inputLabel);
    }
    const alreadyExplained = props["aria-invalid"] && props["aria-invalid"] !== "false"
      && props["aria-describedby"]?.split(/\s+/).some((id) => document.getElementById(id)?.textContent?.trim());
    if (!alreadyExplained) {
      setError(nativeMessage(input, t));
    }
    const first = input.form?.querySelector<HTMLElement>("input:invalid, textarea:invalid, select:invalid");
    (first ?? input).focus();
  };
  const onInput: FormEventHandler<T> = (event) => {
    setError("");
    props.onInput?.(event);
  };
  return {
    error, errorId, clearError, control: {
      onInvalid,
      onInput,
      "aria-label": props["aria-label"] ?? label,
      "aria-labelledby": props["aria-labelledby"] ?? field?.labelId,
      "aria-invalid": error ? true : props["aria-invalid"] ?? (field?.invalid || undefined),
      "aria-describedby": describedBy
    }
  };
}

function nativeLabel(input: Control) {
  const labelElement = input.labels?.[0];
  return (input.getAttribute("aria-label") || labelElement?.querySelector("span")?.textContent || "")
    .replace(/\s*[（(]必填[）)]|\s*\*\s*/g, "").trim();
}

function nativeMessage(input: Control, t: Translator) {
  const label = nativeLabel(input) || t("此项");
  const validity = input.validity;
  if (validity.valueMissing) {
    return t(input.tagName === "SELECT" ? "请选择{0}。" : "请填写{0}。", [label]);
  }
  if (validity.typeMismatch) {
    return input instanceof HTMLInputElement && input.type === "email" ? t("请输入有效的邮箱地址。") : t("请填写有效的{0}。", [label]);
  }
  if (validity.tooShort && "minLength" in input) {
    return t("至少填写 {0} 个字符。", [input.minLength]);
  }
  if (validity.tooLong && "maxLength" in input) {
    return t("最多填写 {0} 个字符。", [input.maxLength]);
  }
  if (input instanceof HTMLInputElement) {
    if (validity.rangeUnderflow) {
      return t(input.type === "date" ? "日期不能早于 {0}。" : "数值不能小于 {0}。", [input.min]);
    }
    if (validity.rangeOverflow) {
      return t(input.type === "date" ? "日期不能晚于 {0}。" : "数值不能大于 {0}。", [input.max]);
    }
  }
  return input.title || t("请按要求填写{0}。", [label]);
}
