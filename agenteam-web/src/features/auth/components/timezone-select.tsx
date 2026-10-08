"use client";

import {useT} from "@/lib/i18n/locale-provider";
import type {ComponentProps} from "react";
import {Select} from "@/components/ui/select";
import {timezoneLabel, timezones} from "@/lib/timezones";
import styles from "./auth.module.css";

export function TimezoneSelect({
                                 value,
                                 onChange,
                                 className = styles.input,
                                 ...props
                               }: Omit<ComponentProps<typeof Select>, "value" | "onChange"> & {
  value: string;
  onChange: (value: string) => void
}) {
  const uiText = useT();
  return <Select {...props} className={className} value={value} onChange={(event) => onChange(event.target.value)}>
    {!timezones.some(([zone]) => zone === value) && <option value={value}>{timezoneLabel(value, uiText)}</option>}
    {timezones.map(([zone, label]) => <option key={zone} value={zone}>{uiText(label)}</option>)}
  </Select>;
}
