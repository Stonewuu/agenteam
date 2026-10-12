"use client";

import type {ComponentProps} from "react";
import {Input as ShadcnInput} from "./shadcn/input";
import {useControlDisabled} from "./fieldset";
import {useNativeFieldError} from "./use-native-field-error";
import {FieldErrorFeedback} from "./error-feedback";
import {DateTimeInput} from "./date-time-input";
import {isDateTimeInput} from "./date-time-value";

export function Input(props: ComponentProps<"input">) {
  return isDateTimeInput(props.type) ? <DateTimeInput {...props} type={props.type}/> : <StandardInput {...props}/>;
}

function StandardInput({disabled, ...props}: ComponentProps<"input">) {
  const validation = useNativeFieldError<HTMLInputElement>(props);
  return <><ShadcnInput {...props} {...validation.control} disabled={useControlDisabled(disabled)}/>
    <FieldErrorFeedback id={validation.errorId} messages={validation.error ? [validation.error] : []}/></>;
}
