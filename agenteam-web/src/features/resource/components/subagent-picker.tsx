"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";

import {useState} from "react";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {IconPlus, IconX} from "@/components/ui/icons";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {type ApiPage, useApiQuery} from "@/lib/http/use-api-query";
import type {UsableVersion} from "../types/resource";
import {SubagentSelectionDialog} from "./subagent-selection-dialog";
import {ResourceVersionSelect} from "./resource-version-select";
import styles from "./subagent-picker.module.css";
import ui from "@/components/ui/surface.module.css";

export function SubagentPicker({enterpriseId, resourceId, selected, onChange, allowed, readOnly}: {
  enterpriseId: string;
  resourceId?: string;
  selected: string[];
  onChange: (ids: string[]) => void;
  allowed: boolean;
  readOnly: boolean;
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const resolved = useApiQuery<ApiPage<UsableVersion>>(allowed && selected.length ? organizationPath(enterpriseId,
    `/resources/usable-versions?kind=agent&versionIds=${encodeURIComponent(selected.join(","))}`) : null);
  const known = new Map(resolved.data?.items.map((item) => [item.versionId, item]));
  const replace = (previous: string, next: UsableVersion) => onChange(selected.map((id) => id === previous ? next.versionId : id)
    .filter((id, index, values) => values.indexOf(id) === index));
  return <div className={styles.picker}>
    {resolved.error &&
      <p className={ui.error} role="alert">{localizeUiMessage(resolved.error ?? "", uiText)}<Button type="button"
                                                                                                    className={ui.button}
                                                                                                    onClick={resolved.retry}>{uiText("重新加载")}</Button>
      </p>}
    <div className={styles.selected} data-subagent-selected>{selected.map((id, index) => {
      const value = known.get(id);
      const name = value?.name ?? (resolved.loading ? uiText("正在读取智能体…") : resolved.data && !resolved.error ? uiText("智能体 {0}（不可用）", [index + 1]) : uiText("已选智能体 {0}", [index + 1]));
      return <div key={id} className={styles.selectedRow}>
        {value && <ResourceAvatar icon={value.icon} color={value.color} size="small"/>}
        <span className={styles.name}>{name}</span>
        {value && (readOnly ? <span className={styles.versionText}>{uiText("版本 ")}{value.versionNo}</span> :
          <ResourceVersionSelect subagentsOnly enterpriseId={enterpriseId} value={value}
                                 onChange={(version) => replace(id, version)}/>)}
        {!readOnly && <Button type="button" className={styles.remove} aria-label={uiText("移除{0}", [name])}
                              onClick={() => onChange(selected.filter((item) => item !== id))}><IconX
          size={16}/></Button>}
      </div>;
    })}</div>
    {!readOnly && allowed && <Button type="button" className={`${ui.button} ${styles.add}`}
                                     disabled={resolved.loading || Boolean(resolved.error)}
                                     onClick={() => setOpen(true)}><IconPlus size={16}/>{uiText("选择智能体")}</Button>}
    {!allowed && !selected.length && <p className={ui.description}>{uiText("暂无可选择的智能体。")}</p>}
    {open &&
      <SubagentSelectionDialog enterpriseId={enterpriseId} resourceId={resourceId} selected={selected} known={known}
                               maximum={8}
                               onConfirm={onChange} onClose={() => setOpen(false)}/>}
  </div>;
}
