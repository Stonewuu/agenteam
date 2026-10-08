"use client";

import {useId} from "react";
import {Switch} from "./shadcn/switch";
import {useControlDisabled} from "./fieldset";

export function Toggle({label, checked, onChange, disabled = false, hideLabel = false}: {
  label: string;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
  hideLabel?: boolean
}) {
  const id = useId();
  return <div className="agenteam-toggle-row"><Switch id={id} className="agenteam-toggle" checked={checked}
                                                      disabled={useControlDisabled(disabled)} onCheckedChange={onChange}
                                                      aria-labelledby={`${id}-label`}/><label id={`${id}-label`}
                                                                                              htmlFor={id}
                                                                                              className={hideLabel ? "sr-only" : undefined}>{label}</label>
  </div>;
}
