"use client";

import type {ComponentProps} from "react";
import {Button as ShadcnButton} from "./shadcn/button";
import {useControlDisabled} from "./fieldset";

export function Button({
                         disabled,
                         type = "submit",
                         variant = "agenteam",
                         size = "agenteam",
                         ...props
                       }: ComponentProps<typeof ShadcnButton>) {
  return <ShadcnButton {...props} type={type} variant={variant} size={size} disabled={useControlDisabled(disabled)}/>;
}
