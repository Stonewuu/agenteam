"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Checkbox} from "@/components/ui/checkbox";
import {Input} from "@/components/ui/input";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {useApiPage} from "@/lib/http/use-api-query";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import type {ResourceKind, UsableVersion} from "../types/resource";
import {ResourceVersionSelect} from "./resource-version-select";
import styles from "./version-picker.module.css";
import ui from "@/components/ui/surface.module.css";

export function VersionSelectionDialog({
                                         enterpriseId,
                                         kind,
                                         title,
                                         selected,
                                         known,
                                         maximum,
                                         subagentsOnly,
                                         excludeResourceId,
                                         onConfirm,
                                         onClose
                                       }: {
  enterpriseId: string;
  kind: ResourceKind;
  title: string;
  selected: string[];
  known: Map<string, UsableVersion>;
  maximum: number;
  subagentsOnly: boolean;
  excludeResourceId?: string;
  onConfirm: (ids: string[], values: Map<string, UsableVersion>) => void;
  onClose: () => void;
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const [chosen, setChosen] = useState(() => new Map(selected.flatMap((id) => known.has(id) ? [[known.get(id)!.resourceId, known.get(id)!] as const] : [])));
  const [versions, setVersions] = useState(() => new Map(chosen));
  const unresolved = selected.filter((id) => !known.has(id));
  const count = chosen.size + unresolved.length;
  const list = useApiPage<UsableVersion>(organizationPath(enterpriseId, `/resources/usable-versions?kind=${kind}&latestOnly=true&query=${encodeURIComponent(query)}${subagentsOnly ? "&subagentsOnly=true" : ""}`));
  const options = list.data?.items.filter((version) => version.resourceId !== excludeResourceId) ?? [];
  const toggle = (value: UsableVersion, checked: boolean) => {
    setChosen((current) => {
      const next = new Map(current);
      if (checked) {
        next.set(value.resourceId, value);
      } else {
        next.delete(value.resourceId);
      }
      return next;
    });
  };
  const selectVersion = (value: UsableVersion) => {
    setVersions((current) => new Map(current).set(value.resourceId, value));
    if (chosen.has(value.resourceId)) {
      toggle(value, true);
    }
  };
  return <Dialog title={uiText("选择{0}", [title])} onClose={onClose} bodyClassName={styles.dialogBody}>
    <Input className={ui.input} type="search" aria-label={uiText("搜索{0}", [title])}
           placeholder={uiText("搜索{0}", [title])} value={query} onChange={(event) => setQuery(event.target.value)}/>
    <div className={styles.results} data-version-options aria-busy={list.loading}>
      <QueryState {...list} hasData={options.length > 0} empty={uiText("暂无可选择的{0}。", [title])}>
        {options.map((latest) => {
          const value = chosen.get(latest.resourceId) ?? versions.get(latest.resourceId) ?? latest;
          const checked = chosen.has(latest.resourceId), disabled = !checked && count >= maximum;
          return <div key={latest.resourceId} className={styles.option} data-version-option={latest.resourceId}
                      data-selected={checked}>
            <label className={styles.choice}><Checkbox aria-label={latest.name} checked={checked} disabled={disabled}
                                                       onCheckedChange={(next) => toggle(value, next)}/>
              <ResourceAvatar icon={value.icon} color={value.color} size="small"/>
              <span className={styles.identity}><strong>{latest.name}</strong><span>{value.description}</span></span>
            </label>
            <ResourceVersionSelect enterpriseId={enterpriseId} value={{...value, name: latest.name}}
                                   onChange={selectVersion} subagentsOnly={subagentsOnly} disabled={disabled}
                                   className={styles.version}/>
          </div>;
        })}
      </QueryState>
    </div>
    <div className={styles.pagination}><Pagination {...list} hasMore={list.data?.hasMore}/></div>
    <DialogActions><span className={styles.count}>{uiText("已选 ")}{count} / {maximum}</span><DialogCancel
      className={ui.button}>{uiText("取消")}</DialogCancel>
      <DialogAction className={ui.primary} disabled={count > maximum} onAction={(close) => close(() => {
        const values = new Map(known);
        for (const value of chosen.values()) {
          values.set(value.versionId, value);
        }
        onConfirm([...unresolved, ...Array.from(chosen.values(), (value) => value.versionId)], values);
      })}>{uiText("确认选择")}</DialogAction>
    </DialogActions>
  </Dialog>;
}
