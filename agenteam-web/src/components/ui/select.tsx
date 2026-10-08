"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {
  Children,
  Fragment,
  isValidElement,
  type ReactNode,
  type SelectHTMLAttributes,
  useEffect,
  useId,
  useRef,
  useState,
} from "react";
import {Select as SelectRoot, SelectContent, SelectItem, SelectTrigger, SelectValue,} from "./shadcn/select";
import {useFieldContext} from "./field";
import {useControlDisabled} from "./fieldset";
import {useNativeFieldError} from "./use-native-field-error";
import {FieldErrorFeedback} from "./error-feedback";

type SelectProps = Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  "multiple" | "size"
> & { renderOption?: (option: { value: string; label: string }) => ReactNode };
type SelectOption = { value: string; label: string; disabled: boolean };

function optionText(children: ReactNode): string {
  return Children.toArray(children)
    .map((child) => {
      if (typeof child === "string" || typeof child === "number") {
        return String(child);
      }
      return isValidElement<{ children?: ReactNode }>(child)
        ? optionText(child.props.children)
        : "";
    })
    .join("");
}

function readOptions(children: ReactNode): SelectOption[] {
  const options: SelectOption[] = [];
  Children.forEach(children, (child) => {
    if (
      !isValidElement<{
        children?: ReactNode;
        value?: string | number;
        disabled?: boolean;
        hidden?: boolean;
      }>(child)
    ) {
      return;
    }
    if (child.type === Fragment) {
      options.push(...readOptions(child.props.children));
    } else if (child.type === "option") {
      const label = optionText(child.props.children);
      options.push({
        value: String(child.props.value ?? label),
        label,
        disabled: Boolean(child.props.disabled || child.props.hidden),
      });
    }
  });
  return options;
}

// 保留原生 select 作为表单值与校验来源，浮层只负责选项展示和键盘操作。
export function Select({
                         children,
                         className = "",
                         style,
                         value,
                         defaultValue,
                         disabled,
                         required,
                         id,
                         autoFocus,
                         onChange,
                         onInvalid,
                         onFocus,
                         renderOption,
                         ...props
                       }: SelectProps) {
  const uiText = useT();
  const options = readOptions(children);
  const initialValue = String(
    defaultValue ?? options.find((option) => !option.disabled)?.value ?? "",
  );
  const [localValue, setLocalValue] = useState(initialValue);
  const [invalid, setInvalid] = useState(false);
  const currentValue = String(value ?? localValue);
  const selected =
    options.find((option) => option.value === currentValue) ??
    options.find((option) => !option.disabled);
  const nativeRef = useRef<HTMLSelectElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const generatedId = useId();
  const field = useFieldContext();
  const inactive = useControlDisabled(disabled);
  const validation = useNativeFieldError<HTMLSelectElement>({...props, onInvalid});
  const clearValidationError = validation.clearError;
  const isInvalid =
    validation.control["aria-invalid"] ?? (invalid || undefined);

  useEffect(() => {
    const form = nativeRef.current?.form;
    const reset = () => {
      setLocalValue(initialValue);
      setInvalid(false);
      clearValidationError();
    };
    form?.addEventListener("reset", reset);
    return () => form?.removeEventListener("reset", reset);
  }, [initialValue, clearValidationError]);

  return (
    <><span className={`select-control ${className}`} style={style}>
      <SelectRoot
        items={options}
        value={selected?.value ?? null}
        disabled={inactive}
        onValueChange={(next) => {
          const native = nativeRef.current;
          if (!native) {
            return;
          }
          native.value = next ?? "";
          native.dispatchEvent(new Event("change", {bubbles: true}));
          native.dispatchEvent(new Event("input", {bubbles: true}));
        }}
      >
        <SelectTrigger
          ref={triggerRef}
          id={id ?? generatedId}
          className="select-trigger"
          autoFocus={autoFocus}
          aria-label={validation.control["aria-label"]}
          aria-labelledby={
            props["aria-labelledby"] ??
            (props["aria-label"] ? undefined : field?.labelId)
          }
          aria-describedby={validation.control["aria-describedby"]}
          aria-invalid={isInvalid}
          aria-required={required || undefined}
          title={props.title}
        >
          <SelectValue
            placeholder={uiText("请选择")}>{selected ? renderOption?.(selected) ?? selected.label : null}</SelectValue>
        </SelectTrigger>
        <SelectContent
          className="select-menu agenteam-popup"
          alignItemWithTrigger={false}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.stopPropagation();
            }
          }}
        >
          {options.map((option) => (
            <SelectItem
              key={option.value}
              value={option.value}
              disabled={option.disabled}
              className="select-option"
            >
              {renderOption?.(option) ?? option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </SelectRoot>
      <select
        {...props}
        {...validation.control}
        ref={nativeRef}
        className="select-native"
        value={currentValue}
        disabled={inactive}
        required={required}
        tabIndex={-1}
        aria-hidden="true"
        onFocus={(event) => {
          triggerRef.current?.focus();
          onFocus?.(event);
        }}
        onInvalid={(event) => {
          validation.control.onInvalid(event);
          event.preventDefault();
          setInvalid(true);
          const firstInvalid = event.currentTarget.form?.querySelector(
            "input:invalid, textarea:invalid, select:invalid",
          );
          if (!firstInvalid || firstInvalid === event.currentTarget) {
            triggerRef.current?.focus();
          }
        }}
        onChange={(event) => {
          setLocalValue(event.currentTarget.value);
          setInvalid(false);
          validation.clearError();
          onChange?.(event);
        }}
      >
        {children}
      </select>
    </span><FieldErrorFeedback id={validation.errorId} messages={validation.error ? [validation.error] : []}/></>
  );
}
