"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useCallback, useEffect, useState} from "react";
import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {Input} from "@/components/ui/input";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {toast} from "@/components/ui/toast";
import {ResourceVersionSelect} from "@/features/resource/components/resource-version-select";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {useApiQuery} from "@/lib/http/use-api-query";
import {apiRequest} from "@/lib/http/api-client";
import type {PluginConfig, PluginToolSelection, UsableVersion} from "@/features/resource/types/resource";
import type {PluginTool, ToolSource} from "../types/plugin";
import {asUsableVersion, changeTool, cleanCollection, collectionSource} from "../lib/plugin-collection";
import {toolDisplayName, toolOperationLabel} from "../lib/tool-display-name";
import styles from "./tool-collection.module.css";
import ui from "@/components/ui/surface.module.css";

export const toolSourcePath = (enterprise: string, id: string, version: string) => organizationPath(enterprise, `/plugins/tool-sources/${encodeURIComponent(id)}/versions/${encodeURIComponent(version)}`);

export function ToolSelectionDialog({enterpriseId, value, known, onConfirm, onClose}: {
  enterpriseId: string;
  value: PluginConfig;
  known: Map<string, ToolSource>;
  onConfirm: (value: PluginConfig, sources: Map<string, ToolSource>) => void;
  onClose: () => void;
}) {
  const uiText = useT();
  const [draft, setDraft] = useState(value);
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(value.sources[0]?.id ?? "");
  const [remembered, setRemembered] = useState(known);
  const [verified, setVerified] = useState(() => new Set<string>());
  const action = useFormAction();
  const directory = useApiQuery<ToolSource[]>(organizationPath(enterpriseId, "/plugins/tool-sources"));
  const options = directory.data ?? [];
  const cache = new Map(remembered);
  for (const item of options) {
    cache.set(item.versionId, item);
  }
  const resolved = useCallback((source: ToolSource) => {
    setRemembered((previous) => previous.get(source.versionId) === source ? previous : new Map(previous).set(source.versionId, source));
    setVerified((previous) => previous.has(source.versionId) ? previous : new Set(previous).add(source.versionId));
  }, []);
  const current = active || options[0]?.resourceId;
  const missing = directory.data ? draft.sources.filter((source) => !options.some((item) => item.resourceId === source.id) && draft.tools.some((tool) => tool.sourceId === source.id)) : [];
  const validVersions = new Set([...verified, ...options.map((source) => source.versionId)]);
  const unresolved = draft.sources.some((source) => draft.tools.some((tool) => tool.sourceId === source.id) && (!source.versionId || !validVersions.has(source.versionId))) || missing.length > 0;
  const search = query.trim().toLocaleLowerCase();
  const noMatches = search && !unresolved && options.every((source) => !(cache.get(draft.sources.find((item) => item.id === source.resourceId)?.versionId ?? "") ?? source).tools
    .some((tool) => `${tool.name} ${toolDisplayName(tool, source.code, uiText)} ${uiText(tool.description)} ${uiText(source.name)}`.toLocaleLowerCase().includes(search)));
  const removeSource = (id: string) => setDraft((previous) => ({
    ...previous,
    tools: previous.tools.filter((tool) => tool.sourceId !== id),
    sources: previous.sources.filter((source) => source.id !== id)
  }));
  const toggle = (source: ToolSource, tool: PluginTool, checked: boolean) => setDraft((previous) => changeTool({
    ...previous,
    sources: [...previous.sources.filter((item) => item.id !== source.resourceId), collectionSource(source)]
  }, source.resourceId, tool.name, checked));
  const selectVersion = (version: UsableVersion) => void action.execute(async () => {
    const source = await apiRequest<ToolSource>(toolSourcePath(enterpriseId, version.resourceId, version.versionId));
    resolved(source);
    const names = new Set(source.tools.map((tool) => tool.name));
    const removed = draft.tools.filter((tool) => tool.sourceId === source.resourceId && !names.has(tool.name)).length;
    setDraft((previous) => ({
      ...previous,
      sources: [...previous.sources.filter((item) => item.id !== source.resourceId), collectionSource(source)],
      tools: previous.tools.filter((tool) => tool.sourceId !== source.resourceId || names.has(tool.name))
    }));
    if (removed) {
      toast.info(uiText("已移除所选版本不提供的 {0} 个工具。", [removed]));
    }
  }, "");
  return <Dialog title={uiText("选择工具")} size="large" onClose={onClose} busy={action.busy}
                 bodyClassName={styles.dialogBody}>
    <Input type="search" className={ui.input} aria-label={uiText("搜索工具")} placeholder={uiText("搜索工具名称或用途")}
           value={query} onChange={(event) => setQuery(event.target.value)}/>
    <div className={styles.browser}>
      <nav className={styles.categories} aria-label={uiText("工具能力分类")}>{options.map((source) => <Button
        key={source.resourceId} type="button" className={styles.category}
        data-selected={current === source.resourceId} onClick={() => {
        setActive(source.resourceId);
        setQuery("");
      }}>
        <ResourceAvatar icon={source.icon} color={source.color}
                        size="small"/><span>{uiText(source.name)}</span><small>{draft.tools.filter((tool) => tool.sourceId === source.resourceId).length}</small>
      </Button>)}{missing.map((source) => <Button key={source.id} className={styles.category}
                                                  data-selected={current === source.id} type="button"
                                                  onClick={() => setActive(source.id)}>{uiText("原有工具来源")}</Button>)}</nav>
      <div className={styles.results} data-tool-options>
        <QueryState {...directory} hasData={options.length > 0 || missing.length > 0}
                    empty={uiText("暂无可选择的工具。")}>
          {noMatches && <p className={styles.empty}>{uiText("没有找到匹配的工具。")}</p>}
          {options.map((source) => <ToolPickerGroup key={source.resourceId} enterpriseId={enterpriseId} latest={source}
                                                    versionId={draft.sources.find((item) => item.id === source.resourceId)?.versionId || source.versionId}
                                                    known={cache}
                                                    query={query} hidden={!query && current !== source.resourceId}
                                                    selections={draft.tools} busy={action.busy}
                                                    onResolved={resolved} onToggle={toggle} onVersion={selectVersion}
                                                    onClear={() => removeSource(source.resourceId)}/>)}
          {missing.map((source) => <div key={source.id} hidden={!query && current !== source.id}
                                        className={styles.unavailable}>
            <p>{uiText("原工具来源当前不可用，可以移除后重新选择。")}</p>
            <Button type="button" className={ui.button}
                    onClick={() => removeSource(source.id)}>{uiText("移除此来源的工具")}</Button></div>)}
        </QueryState>
      </div>
    </div>
    <DialogActions><span className={styles.count}>{uiText("已选 ")}{draft.tools.length} / 100</span><DialogCancel
      className={ui.button}>{uiText("取消")}</DialogCancel>
      <DialogAction className={ui.primary}
                    disabled={action.busy || unresolved || directory.loading || Boolean(directory.error)}
                    onAction={(close) => close(() => onConfirm(cleanCollection(draft), cache))}>{uiText("确认选择")}</DialogAction>
    </DialogActions>
  </Dialog>;
}

function ToolPickerGroup({
                           enterpriseId,
                           latest,
                           versionId,
                           known,
                           query,
                           hidden,
                           selections,
                           busy,
                           onResolved,
                           onToggle,
                           onVersion,
                           onClear
                         }: {
  enterpriseId: string;
  latest: ToolSource;
  versionId: string;
  known: Map<string, ToolSource>;
  query: string;
  hidden: boolean;
  selections: PluginToolSelection[];
  busy: boolean;
  onResolved: (source: ToolSource) => void;
  onToggle: (source: ToolSource, tool: PluginTool, checked: boolean) => void;
  onVersion: (version: UsableVersion) => void;
  onClear: () => void;
}) {
  const uiText = useT();
  const existing = known.get(versionId);
  const request = useApiQuery<ToolSource>(versionId !== latest.versionId ? toolSourcePath(enterpriseId, latest.resourceId, versionId) : null);
  const source = request.error ? null : request.data ?? existing;
  useEffect(() => {
    if (request.data) {
      onResolved(request.data);
    }
  }, [request.data, onResolved]);
  const normalized = query.trim().toLocaleLowerCase();
  const choices = source?.tools.filter((tool) => `${tool.displayName ?? tool.name} ${tool.description} ${uiText(source.name)}`.toLocaleLowerCase().includes(normalized)) ?? [];
  return <section hidden={hidden || Boolean(source && normalized && !choices.length)} className={styles.group}
                  aria-label={uiText(latest.name)}>
    <div className={styles.groupHeading}>
      <div><strong>{uiText(latest.name)}</strong><p>{uiText(source?.description ?? latest.description)}</p></div>
      {source && <ResourceVersionSelect enterpriseId={enterpriseId} value={asUsableVersion(source)} onChange={onVersion}
                                        disabled={busy} className={styles.version}/>}
    </div>
    <QueryState {...request} hasData={Boolean(source)} empty={uiText("此版本暂不可用。")}>{source &&
      <div className={styles.toolList}>{choices.map((tool) => {
        const checked = selections.some((item) => item.sourceId === source.resourceId && item.name === tool.name);
        const labelId = `tool-choice-${source.resourceId}-${tool.name}`;
        return <label key={tool.name} className={styles.toolOption} data-selected={checked}
                      data-tool-option={`${source.resourceId}:${tool.name}`}>
          <Checkbox aria-labelledby={labelId} checked={checked}
                    disabled={busy || request.loading || !checked && selections.length >= 100}
                    onCheckedChange={(value) => onToggle(source, tool, value)}/>
          <span id={labelId}
                className="sr-only">{uiText(source.name)} · {toolDisplayName(tool, source.code, uiText)}</span>
          <ResourceAvatar icon={source.icon} color={source.color} size="small"/>
          <span
            className={styles.identity}><strong>{toolDisplayName(tool, source.code, uiText)}</strong><span>{tool.description}</span></span>
          <span
            className={`badge ${tool.operationClass === "read" ? "blue" : "amber"}`}>{uiText(toolOperationLabel(tool.operationClass))}</span>
        </label>;
      })}</div>}</QueryState>
    {request.error &&
      <Button type="button" className={ui.button} onClick={onClear}>{uiText("移除此来源的工具")}</Button>}
  </section>;
}
