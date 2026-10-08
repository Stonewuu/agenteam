"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {useState} from "react";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useApiPage} from "@/lib/http/use-api-query";
import type {UsableVersion} from "../types/resource";
import {ResourceVersionSelect} from "./resource-version-select";
import styles from "./subagent-picker.module.css";
import ui from "@/components/ui/surface.module.css";

export function SubagentSelectionDialog({enterpriseId, resourceId, selected, known, maximum, onConfirm, onClose}: {
  enterpriseId: string; resourceId?: string; selected: string[]; known: Map<string, UsableVersion>; maximum: number;
  onConfirm: (ids: string[]) => void; onClose: () => void;
}) {
  const uiText = useT();
  const [query, setQuery] = useState("");
  const [chosen, setChosen] = useState(() => new Map(selected.flatMap((id) => known.has(id) ? [[known.get(id)!.resourceId, known.get(id)!] as const] : [])));
  const [versions, setVersions] = useState(() => new Map(chosen));
  const unresolved = selected.filter((id) => !known.has(id));
  const count = chosen.size + unresolved.length;
  const list = useApiPage<UsableVersion>(organizationPath(enterpriseId, `/resources/usable-versions?kind=agent&subagentsOnly=true&query=${encodeURIComponent(query)}`));
  const options = list.data?.items.filter((item) => item.resourceId !== resourceId) ?? [];
  const toggle = (value: UsableVersion, checked: boolean) => setChosen((current) => {
    const next = new Map(current);
    if (checked) {
      next.set(value.resourceId, value);
    } else {
      next.delete(value.resourceId);
    }
    return next;
  });
  const selectVersion = (value: UsableVersion) => {
    setVersions((current) => new Map(current).set(value.resourceId, value));
    if (chosen.has(value.resourceId)) {
      toggle(value, true);
    }
  };
  return <Dialog title={uiText("选择子智能体")} onClose={onClose} bodyClassName={styles.dialogBody}>
    <Input className={ui.input} type="search" value={query} onChange={(event) => setQuery(event.target.value)}
           aria-label={uiText("搜索智能体")} placeholder={uiText("搜索智能体")}/>
    <div className={styles.results} data-subagent-options aria-busy={list.loading}>
      <QueryState {...list} hasData={options.length > 0} empty={uiText("暂无可选择的智能体。")}>
        {options.map((latest) => {
          const value = chosen.get(latest.resourceId) ?? versions.get(latest.resourceId) ?? latest;
          const checked = chosen.has(latest.resourceId);
          const disabled = !checked && count >= maximum;
          return <div className={styles.option} key={latest.resourceId} data-subagent-resource={latest.resourceId}
                      data-selected={checked}>
            <label className={styles.choice}>
              <Checkbox checked={checked} disabled={disabled} aria-label={latest.name}
                        onCheckedChange={(checked) => toggle(value, checked)}/>
              <ResourceAvatar icon={value.icon} color={value.color} size="small"/>
              <span className={styles.identity}><strong>{latest.name}</strong><span>{value.description}</span></span>
            </label>
            <ResourceVersionSelect subagentsOnly enterpriseId={enterpriseId} value={{...value, name: latest.name}}
                                   onChange={selectVersion} disabled={disabled}/>
          </div>;
        })}
      </QueryState>
    </div>
    <div className={styles.pagination}><Pagination {...list} hasMore={list.data?.hasMore}/></div>
    <DialogActions><span className={styles.count}>{uiText("已选 ")}{count} / {maximum}</span><DialogCancel
      className={ui.button}>{uiText("取消")}</DialogCancel>
      <DialogAction className={ui.primary} disabled={count > maximum}
                    onAction={(close) => close(() => onConfirm([...unresolved, ...Array.from(chosen.values(), (item) => item.versionId)]))}>{uiText("确认选择")}</DialogAction>
    </DialogActions>
  </Dialog>;
}
