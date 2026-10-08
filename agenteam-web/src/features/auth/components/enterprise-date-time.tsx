"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {createContext, useContext} from "react";

export const EnterpriseTimezone = createContext<string | null>(null);

export function EnterpriseDateTime({value, dateOnly = false, timeOnly = false, compact = false, includeYear = false}: {
  value: string;
  dateOnly?: boolean;
  timeOnly?: boolean;
  compact?: boolean;
  includeYear?: boolean
}) {
  const uiText = useT();
  const timezone = useContext(EnterpriseTimezone);
  if (!timezone) {
    return null;
  }
  const date = new Date(value);
  const full = date.toLocaleString(uiText.formatLocale, {
    timeZone: timezone,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
  return <time dateTime={value} title={`${full} · ${timezone}`}>{compact ? date.toLocaleString(uiText.formatLocale, {
    timeZone: timezone,
    hour12: false,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }) : timeOnly ? date.toLocaleTimeString(uiText.formatLocale, {
    timeZone: timezone,
    hour12: false,
    hour: "2-digit",
    minute: "2-digit"
  }) : dateOnly ? date.toLocaleDateString(uiText.formatLocale, {
    timeZone: timezone, ...(includeYear ? {year: "numeric" as const} : {}),
    month: "2-digit",
    day: "2-digit"
  }) : full}</time>;
}
