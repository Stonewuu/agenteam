"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {Fieldset} from "@/components/ui/fieldset";
import {Button} from "@/components/ui/button";

import {useState} from "react";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {type ApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import type {ResourceKind, UsableVersion} from "../types/resource";
import ui from "@/components/ui/surface.module.css";
import styles from "./resource.module.css";
import picker from "./version-picker.module.css";
import {VersionSelectionDialog} from "./version-selection-dialog";

export function VersionPicker({
                                enterpriseId,
                                kind,
                                title,
                                selected,
                                onChange,
                                maximum,
                                allowed,
                                readOnly = false,
                                subagentsOnly = false,
                                excludeResourceId
                              }: {
  enterpriseId: string;
  kind: ResourceKind;
  title: string;
  selected: string[];
  onChange: (values: string[]) => void;
  maximum: number;
  allowed: boolean;
  readOnly?: boolean;
  subagentsOnly?: boolean;
  excludeResourceId?: string;
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [remembered, setRemembered] = useState(() => new Map<string, UsableVersion>());
  const selectedPath = allowed && selected.length ? organizationPath(enterpriseId, `/resources/usable-versions?kind=${kind}&versionIds=${encodeURIComponent(selected.join(","))}`) : null;
  const resolved = useApiQuery<ApiPage<UsableVersion>>(selectedPath);
  const known = new Map(remembered);
  for (const value of resolved.data?.items ?? []) {
    known.set(value.versionId, value);
  }
  if (!resolved.loading && !resolved.error && resolved.data) {
    const available = new Set(resolved.data.items.map((value) => value.versionId));
    for (const id of selected) {
      if (!available.has(id)) {
        known.delete(id);
      }
    }
  }
  return <Fieldset className={styles.section}>
    <legend>{title}</legend>
    {resolved.error && <p className={ui.error} role="alert">{localizeUiMessage(resolved.error ?? "", uiText)}<Button
      className={ui.button} type="button" onClick={resolved.retry}>{uiText("重新读取")}</Button></p>}
    <div className={picker.selected} data-selected-versions={kind}>{selected.map((id, index) => <div
      className={picker.selectedRow} key={id}>
      <span className={picker.summary}>{known.get(id) &&
        <ResourceAvatar icon={known.get(id)!.icon} color={known.get(id)!.color}
                        size="small"/>}<span>{known.get(id) ? uiText("{0} · 版本 {1}", [known.get(id)!.name, known.get(id)!.versionNo]) : resolved.loading ? uiText("正在读取已选内容…") : resolved.data && !resolved.error ? uiText("{0} {1}（不可用）", [title, index + 1]) : uiText("已选{0} {1}", [title, index + 1])}</span></span>
      {!readOnly && <Button className={ui.button} type="button"
                            aria-label={uiText("移除{0}版本 {1}", [known.get(id)?.name ?? title, known.get(id)?.versionNo ?? index + 1])}
                            onClick={() => {
                              setRemembered(known);
                              onChange(selected.filter((value) => value !== id));
                            }}>{uiText("移除")}</Button>}
    </div>)}</div>
    {!readOnly && allowed && <Button className={`${ui.button} ${picker.add}`} type="button" onClick={() => {
      setRemembered(known);
      setOpen(true);
    }} aria-haspopup="dialog" aria-expanded={open}>{uiText("选择")}{title}</Button>}
    {!allowed && !selected.length && <p className={ui.description}>{uiText("暂无可选择的")}{title}。</p>}
    {open &&
      <VersionSelectionDialog enterpriseId={enterpriseId} kind={kind} title={title} selected={selected} known={known}
                              maximum={maximum} subagentsOnly={subagentsOnly} excludeResourceId={excludeResourceId}
                              onClose={() => setOpen(false)} onConfirm={(ids, values) => {
        setRemembered(values);
        onChange(ids);
        setOpen(false);
      }}/>}
  </Fieldset>;
}
