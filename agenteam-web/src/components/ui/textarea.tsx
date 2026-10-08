"use client";

import type {ComponentProps} from "react";
import {Textarea as ShadcnTextarea} from "./shadcn/textarea";
import {useControlDisabled} from "./fieldset";
import {useNativeFieldError} from "./use-native-field-error";
import {FieldErrorFeedback} from "./error-feedback";

export function Textarea({disabled, ...props}: ComponentProps<"textarea">) {
  const validation = useNativeFieldError<HTMLTextAreaElement>(props);
  return <><ShadcnTextarea {...props} {...validation.control} disabled={useControlDisabled(disabled)}/>
    <FieldErrorFeedback id={validation.errorId} messages={validation.error ? [validation.error] : []}/></>;
}
