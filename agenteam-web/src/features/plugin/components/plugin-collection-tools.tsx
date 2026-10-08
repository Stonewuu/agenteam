"use client";

import {localizeUiMessage} from "@/lib/i18n/ui-message";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {IconPlus, IconX} from "@/components/ui/icons";
import {ResourceAvatar} from "@/components/ui/resource-avatar";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {PluginConfig, PluginSource, PluginToolSelection} from "@/features/resource/types/resource";
import type {ToolSource} from "../types/plugin";
import {cleanCollection} from "../lib/plugin-collection";
import {ToolSelectionDialog, toolSourcePath} from "./tool-selection-dialog";
import {toolDisplayName, toolOperationLabel} from "../lib/tool-display-name";
import styles from "./tool-collection.module.css";
import ui from "@/components/ui/surface.module.css";

export function PluginCollectionTools({enterpriseId, value, onChange, readOnly, allowed}: {
  enterpriseId: string;
  value: PluginConfig;
  onChange: (value: PluginConfig) => void;
  readOnly: boolean;
  allowed: boolean;
}) {
  const uiText = useT();
  const [open, setOpen] = useState(false);
  const [known, setKnown] = useState(() => new Map<string, ToolSource>());
  const remove = (id: string) => onChange(cleanCollection({
    ...value,
    tools: value.tools.filter((tool) => tool.id !== id)
  }));
  return <section className={styles.collection} aria-label={uiText("已选工具")}>
    <div className={styles.heading}><h3>{uiText("工具")}{value.tools.length > 0 &&
      <small>{value.tools.length}</small>}</h3>
      {!readOnly && <Button type="button" className={ui.button} disabled={!allowed} onClick={() => setOpen(true)}
                            aria-haspopup="dialog"><IconPlus size={16}/>{uiText("选择工具")}</Button>}</div>
    {!value.tools.length && <p className={styles.empty}>{uiText("选择需要的工具，组合成适合当前工作的插件。")}</p>}
    {value.sources.filter((source) => value.tools.some((tool) => tool.sourceId === source.id)).map((source) =>
      <SelectedSource key={source.id} enterpriseId={enterpriseId}
                      source={source} known={known.get(source.versionId ?? "")}
                      selections={value.tools.filter((tool) => tool.sourceId === source.id)} readOnly={readOnly}
                      onRemove={remove}/>)}
    {open && <ToolSelectionDialog enterpriseId={enterpriseId} value={value} known={known} onClose={() => setOpen(false)}
                                  onConfirm={(next, sources) => {
                                    setKnown(sources);
                                    onChange(next);
                                    setOpen(false);
                                  }}/>}
  </section>;
}

function SelectedSource({enterpriseId, source, known, selections, readOnly, onRemove}: {
  enterpriseId: string;
  source: PluginSource;
  known?: ToolSource;
  selections: PluginToolSelection[];
  readOnly: boolean;
  onRemove: (id: string) => void;
}) {
  const uiText = useT();
  const request = useApiQuery<ToolSource>(source.versionId && !known ? toolSourcePath(enterpriseId, source.id, source.versionId) : null);
  const value = known ?? request.data;
  return <div className={styles.selectedGroup} data-selected-tool-source={source.id}>
    <header>{value && <ResourceAvatar icon={value.icon} color={value.color}
                                      size="small"/>}<strong>{value?.name ?? (request.loading ? uiText("正在读取工具来源…") : uiText("原工具来源不可用"))}</strong>
      {value && <span className={styles.versionLabel}>{uiText("版本 ")}{value.versionNo}</span>}</header>
    {request.error &&
      <p className={ui.error} role="alert">{localizeUiMessage(request.error ?? "", uiText)}<Button type="button"
                                                                                                   className={ui.button}
                                                                                                   onClick={request.retry}>{uiText("重新读取")}</Button>
      </p>}
    {selections.map((selection) => {
      const tool = value?.tools.find((item) => item.name === selection.name);
      const name = tool ? toolDisplayName(tool, value?.code, uiText) : request.loading ? uiText("正在读取工具…") : uiText("此工具已不可用");
      return <div key={selection.id} className={styles.selectedTool}><span><strong>{name}</strong>{tool &&
        <span>{tool.description}</span>}</span>
        {tool && <span
          className={`badge ${tool.operationClass === "read" ? "blue" : "amber"}`}>{uiText(toolOperationLabel(tool.operationClass))}</span>}
        {!readOnly && <Button type="button" className="icon-button" aria-label={uiText("移除{0}", [name])}
                              onClick={() => onRemove(selection.id)}><IconX size={16}/></Button>}
      </div>;
    })}
  </div>;
}
