"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from "@/components/ui/shadcn/select";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiPage} from "@/lib/http/use-api-query";
import type {UsableVersion} from "../types/resource";
import styles from "./subagent-picker.module.css";

export function ResourceVersionSelect({
                                        enterpriseId,
                                        value,
                                        onChange,
                                        disabled = false,
                                        subagentsOnly = false,
                                        className = styles.version
                                      }: {
  enterpriseId: string;
  value: UsableVersion;
  onChange: (version: UsableVersion) => void;
  disabled?: boolean;
  subagentsOnly?: boolean;
  className?: string;
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const versions = useApiPage<UsableVersion>(organizationPath(enterpriseId,
    `/resources/usable-versions?kind=${value.kind}${subagentsOnly ? "&subagentsOnly=true" : ""}&resourceId=${encodeURIComponent(value.resourceId)}`), 0, 0, 30, loaded);
  const items = versions.data?.items ?? [];
  const choices = items.some((item) => item.versionId === value.versionId) ? items : [value, ...items];
  return <Select open={open} onOpenChange={(next) => {
    setOpen(next);
    if (next) {
      setLoaded(true);
    }
  }} value={value.versionId} disabled={disabled}
                 items={choices.map((item) => ({value: item.versionId, label: uiText("版本 {0}", [item.versionNo])}))}
                 onValueChange={(id) => {
                   const selected = choices.find((item) => item.versionId === id);
                   if (selected) {
                     onChange(selected);
                   }
                 }}>
    <SelectTrigger className={`select-trigger ${className}`}
                   aria-label={uiText("{0}的版本", [value.name])}><SelectValue>{uiText("版本 ")}{value.versionNo}</SelectValue></SelectTrigger>
    <SelectContent className="select-menu agenteam-popup" alignItemWithTrigger={false} onKeyDown={(event) => {
      if (event.key === "Escape") {
        event.stopPropagation();
      }
    }}>
      <QueryState {...versions} hasData={items.length > 0} empty={uiText("没有其他可用版本。")}>
        {choices.map((item) => <SelectItem key={item.versionId} value={item.versionId}
                                           className="select-option">{uiText("版本 ")}{item.versionNo}</SelectItem>)}
      </QueryState>
      <Pagination {...versions} hasMore={versions.data?.hasMore}/>
    </SelectContent>
  </Select>;
}
