"use client";

import type {ComponentProps} from "react";
import {Checkbox as ShadcnCheckbox} from "./shadcn/checkbox";
import {useControlDisabled} from "./fieldset";

export function Checkbox({disabled, className = "", ...props}: ComponentProps<typeof ShadcnCheckbox>) {
  return <ShadcnCheckbox {...props} className={`agenteam-checkbox ${className}`}
                         disabled={useControlDisabled(disabled)}/>;
}
