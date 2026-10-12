"use client";

import {type ComponentProps, useEffect, useId, useImperativeHandle, useRef, useState} from "react";
import {Input as TextInput} from "./shadcn/input";
import {DateTimePicker} from "./date-time-picker";
import {Button} from "./button";
import {Calendar} from "./calendar";
import {TimePickerFields} from "./time-picker-fields";
import {useControlDisabled} from "./fieldset";
import {useFieldContext} from "./field";
import {useNativeFieldError} from "./use-native-field-error";
import {FieldErrorFeedback} from "./error-feedback";
import {IconCalendar, IconClock} from "./icons";
import {useT} from "@/lib/i18n/locale-provider";
import {currentDateTime, dateTimeAllowed, dateTimeText, dateValue, dateWithinBounds, normalizeDateTime, parseDate, timePart, type DateTimeInputType} from "./date-time-value";
import ui from "./surface.module.css";
import styles from "./date-time-input.module.css";

type Props = ComponentProps<"input"> & {type: DateTimeInputType};

/** 隐藏字段保留表单值和浏览器约束校验，用户只操作平台文本框与选择面板。 */
export function DateTimeInput({
  type, value, defaultValue, ref, className, disabled, readOnly, required, min, max, step, name, form,
  onChange, onInput, onInvalid, onBlur, onClick, onKeyDown, placeholder, ...props
}: Props) {
  const t = useT();
  const field = useFieldContext();
  const inactive = useControlDisabled(disabled);
  const validation = useNativeFieldError<HTMLInputElement>({...props, onInput, onInvalid});
  const clearError = validation.clearError;
  const native = useRef<HTMLInputElement>(null);
  const visible = useRef<HTMLInputElement>(null);
  const pickerLabel = useId();
  const [local, setLocal] = useState(String(defaultValue ?? ""));
  const current = String(value ?? local);
  const [editing, setEditing] = useState({value: current, text: dateTimeText(current)});
  const [touched, setTouched] = useState(false);
  const [open, setOpen] = useState(false);
  const [focusCalendar, setFocusCalendar] = useState(true);
  const [draft, setDraft] = useState(current);
  const [month, setMonth] = useState(() => parseDate(current.slice(0, 10)) ?? new Date());
  const [now, setNow] = useState(() => new Date());
  const seconds = step === "any" || Number(step ?? 60) < 60 || timePart(current, type).length > 5;
  const syntax = normalizeDateTime(editing.text, type) === null;
  const formatError = type === "date" ? t("请输入有效日期，例如 2026-10-12。")
    : type === "time" ? t("请输入有效时间，例如 09:30。") : t("请输入有效日期和时间，例如 2026-10-12 09:30。");
  const feedback = field?.invalid ? "" : touched && syntax ? formatError : validation.error;
  const description = [validation.control["aria-describedby"], feedback && validation.errorId].filter(Boolean).join(" ") || undefined;
  const invalid = Boolean(feedback) || validation.control["aria-invalid"];
  const Icon = type === "time" ? IconClock : IconCalendar;
  const title = type === "date" ? t("选择日期") : type === "time" ? t("选择时间") : t("选择日期和时间");

  if (editing.value !== current) {
    setEditing({value: current, text: dateTimeText(current)});
    setTouched(false);
  }
  if ((inactive || readOnly) && open) {
    setOpen(false);
  }
  useImperativeHandle(ref, () => native.current!, []);
  useEffect(() => {
    native.current?.setCustomValidity(syntax ? formatError : "");
  }, [syntax, formatError]);
  useEffect(() => {
    const owner = native.current?.form;
    const reset = () => {
      const next = String(value ?? defaultValue ?? "");
      setLocal(next);
      setEditing({value: next, text: dateTimeText(next)});
      setTouched(false);
      setOpen(false);
      native.current?.setCustomValidity("");
      clearError();
    };
    owner?.addEventListener("reset", reset);
    return () => owner?.removeEventListener("reset", reset);
  }, [value, defaultValue, clearError]);

  function allowed(candidate: string) {
    return dateTimeAllowed(candidate, {type, min, max, step, base: current});
  }

  function emit(next: string, text = dateTimeText(next)) {
    const input = native.current;
    if (!input) {
      return;
    }
    input.setCustomValidity("");
    setEditing({value: next, text});
    setTouched(false);
    validation.clearError();
    if (open) {
      setDraft(next);
      const date = parseDate(next.slice(0, 10));
      if (date) {
        setMonth(date);
      }
    }
    // 使用原生值设置器触发 React 表单事件，调用方仍收到原类型、原格式的字段值。
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, next);
    input.dispatchEvent(new Event("input", {bubbles: true}));
    input.dispatchEvent(new Event("change", {bubbles: true}));
  }

  function openChanged(next: boolean, fromInput = false) {
    if (next) {
      setFocusCalendar(!fromInput);
      const clock = new Date();
      setNow(clock);
      setDraft(current || (type === "date" ? "" : currentDateTime(type, seconds, clock)));
      setMonth(parseDate(current.slice(0, 10)) ?? clock);
    }
    setOpen(next);
  }

  function choose(next: string) {
    emit(next);
    setOpen(false);
  }

  const minimum = typeof min === "string" ? parseDate(min.slice(0, 10)) : undefined;
  const maximum = typeof max === "string" ? parseDate(max.slice(0, 10)) : undefined;
  const shortcut = currentDateTime(type, seconds, now);
  return <><span className={styles.control}>
    <TextInput {...props} ref={visible} type="text" className={`${className ?? ""} ${styles.entry}`}
      value={editing.text} disabled={inactive} readOnly={readOnly} form={form}
      placeholder={placeholder ?? (type === "date" ? "YYYY-MM-DD" : type === "time" ? "HH:mm" : "YYYY-MM-DD HH:mm")}
      autoComplete={props.autoComplete ?? "off"} spellCheck={false}
      aria-label={validation.control["aria-label"]} aria-labelledby={validation.control["aria-labelledby"]}
      aria-describedby={description} aria-invalid={invalid} aria-required={required || undefined}
      data-date-time-input={type} aria-keyshortcuts="Alt+ArrowDown"
      aria-haspopup="dialog" aria-expanded={open}
      onClick={(event) => {
        onClick?.(event);
        if (!event.defaultPrevented && !inactive && !readOnly && !open) {
          openChanged(true, true);
        }
      }}
      onChange={(event) => {
        const text = event.target.value;
        const normalized = normalizeDateTime(text, type);
        validation.clearError();
        if (normalized !== null) {
          emit(normalized, text);
        } else {
          setEditing({value: current, text});
          native.current?.setCustomValidity(formatError);
        }
      }}
      onBlur={(event) => {
        setTouched(true);
        const normalized = normalizeDateTime(editing.text, type);
        if (normalized !== null) {
          setEditing({value: current, text: dateTimeText(normalized)});
        }
        onBlur?.(event);
      }}
      onKeyDown={(event) => {
        onKeyDown?.(event);
        if (!event.defaultPrevented && open && event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          setOpen(false);
          return;
        }
        if (!event.defaultPrevented && event.altKey && event.key === "ArrowDown" && !inactive && !readOnly) {
          event.preventDefault();
          openChanged(true);
        }
      }}/>
    <DateTimePicker open={open} onOpenChange={openChanged} title={title} finalFocus={visible} focusCalendar={focusCalendar}
      trigger={<Button type="button" className={`icon-button ${styles.trigger}`} disabled={inactive || readOnly}
        aria-label={!field && props["aria-label"] ? `${props["aria-label"]} · ${title}` : undefined}
        aria-labelledby={field ? `${field.labelId} ${pickerLabel}` : !props["aria-label"] ? pickerLabel : undefined}>
        <Icon size={18}/><span id={pickerLabel} className={styles.srOnly}>{title}</span>
      </Button>}>
        {type !== "time" && <Calendar month={month} onMonthChange={setMonth} value={parseDate(draft.slice(0, 10))}
          min={minimum} max={maximum} disabled={(day) => type === "date" ? !allowed(dateValue(day))
            : !dateWithinBounds(day, minimum, maximum)}
          onChange={(day) => {
            const date = dateValue(day);
            if (type === "date") {
              choose(date);
            } else {
              setDraft(`${date}T${timePart(draft, type)}`);
            }
          }}/>}
        {type !== "date" && <div className={`${styles.timeSection} ${type === "time" ? styles.timeOnly : ""}`}>
          <div className={styles.timeHeading}><IconClock size={17}/><strong>{t("选择时间")}</strong></div>
          <TimePickerFields value={timePart(draft, type)} seconds={seconds} onChange={(time) => {
            setDraft(type === "time" ? time : `${draft.slice(0, 10) || dateValue(now)}T${time}`);
          }}/>
          {draft && !allowed(draft) && <p className={styles.rangeError} role="status">{t("所选日期或时间不符合范围或间隔要求。")}</p>}
        </div>}
        <div className={styles.footer}>
          <Button type="button" className={ui.button} disabled={!current && !editing.text} onClick={() => choose("")}>{t("清空")}</Button>
          <Button type="button" className={ui.button} disabled={!allowed(shortcut)} onClick={() => {
            if (type === "date") {
              choose(shortcut);
            } else {
              setDraft(shortcut);
              setMonth(now);
            }
          }}>{type === "date" ? t("今天") : t("现在")}</Button>
          {type !== "date" && <Button type="button" className={ui.primary} disabled={!allowed(draft)} onClick={() => choose(draft)}>{t("确定")}</Button>}
        </div>
    </DateTimePicker>
    <input ref={native} type={type} name={name} form={form} className={styles.native} tabIndex={-1} aria-hidden="true"
      value={current} min={min} max={max} step={step} disabled={inactive} readOnly={readOnly} required={required}
      {...validation.control} onFocus={() => visible.current?.focus()}
      onInvalid={(event) => {
        validation.control.onInvalid(event);
        event.preventDefault();
        setTouched(true);
        const first = event.currentTarget.form?.querySelector("input:invalid, select:invalid, textarea:invalid");
        if (!first || first === event.currentTarget) {
          visible.current?.focus();
        }
      }}
      onChange={(event) => {
        setLocal(event.target.value);
        onChange?.(event);
      }}/>
  </span><FieldErrorFeedback id={validation.errorId} messages={feedback ? [feedback] : []}/></>;
}
