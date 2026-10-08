"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Select} from "@/components/ui/select";


import ui from "@/components/ui/surface.module.css";

export function recentTimeRange(days: number) {
  const end = new Date();
  return {from: new Date(end.getTime() - days * 86_400_000).toISOString(), to: end.toISOString()};
}

export function HistoryTimeFilter({days, onChange, compact = false}: {
  days: number;
  onChange: (days: number) => void;
  compact?: boolean
}) {
  const uiText = useT();
  return <label className={ui.field}><span
    className={compact ? "sr-only" : undefined}>{uiText("时间范围")}</span><Select className={ui.select} value={days}
                                                                                   onChange={(event) => onChange(Number(event.target.value))}>
    <option value={7}>{uiText("最近七天")}</option>
    <option value={30}>{uiText("最近三十天")}</option>
    <option value={90}>{uiText("最近九十天")}</option>
  </Select></label>;
}
