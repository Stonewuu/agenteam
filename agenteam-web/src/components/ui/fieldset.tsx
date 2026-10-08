"use client";

import {type ComponentProps, createContext, useContext} from "react";
import {FieldSet} from "./shadcn/field";

const DisabledContext = createContext(false);

export function useControlDisabled(disabled?: boolean) {
  return useContext(DisabledContext) || Boolean(disabled);
}

/** 弹层仍继承表单禁用状态，避免只读表单里的自定义控件继续响应。 */
export function Fieldset({disabled, ...props}: ComponentProps<"fieldset">) {
  const inactive = useControlDisabled(disabled);
  return <DisabledContext value={inactive}><FieldSet {...props} disabled={inactive}/></DisabledContext>;
}
