"use client";

import type {ComponentProps} from "react";
import {RadioGroup as ShadcnRadioGroup, RadioGroupItem as ShadcnRadioGroupItem} from "./shadcn/radio-group";
import {useControlDisabled} from "./fieldset";

export function RadioGroup({disabled, ...props}: ComponentProps<typeof ShadcnRadioGroup>) {
  return <ShadcnRadioGroup {...props} disabled={useControlDisabled(disabled)}/>;
}

export function RadioGroupItem({disabled, className = "", ...props}: ComponentProps<typeof ShadcnRadioGroupItem>) {
  return <ShadcnRadioGroupItem {...props} className={`agenteam-radio ${className}`}
                               disabled={useControlDisabled(disabled)}/>;
}
