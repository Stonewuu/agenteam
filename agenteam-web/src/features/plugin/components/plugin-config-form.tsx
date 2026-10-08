"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {useState} from "react";
import {Select} from "@/components/ui/select";
import {Dialog, DialogAction, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {type FieldErrors, NumberField, Section, TextField} from "@/features/resource/components/resource-fields";
import type {PluginConfig, PluginSource} from "@/features/resource/types/resource";
import {CredentialPicker} from "./credential-picker";
import {PluginCollectionTools} from "./plugin-collection-tools";
import {remoteSource} from "../lib/plugin-collection";
import ui from "@/components/ui/surface.module.css";

export function PluginConfigForm({enterpriseId, value, onChange, permissions, errors, readOnly}: {
  enterpriseId: string;
  value: PluginConfig;
  onChange: (value: PluginConfig) => void;
  permissions: string[];
  errors: FieldErrors;
  readOnly: boolean;
}) {
  const uiText = useT();
  const remote = remoteSource(value);
  const [switching, setSwitching] = useState<"collection" | "mcp" | null>(null);
  const applyType = (mode: "collection" | "mcp") => {
    onChange({
      ...value, tools: [], sources: mode === "mcp"
        ? [{id: "remote", type: "mcp", transport: "streamable_http", endpoint: "", credentialId: null}] : []
    });
    setSwitching(null);
  };
  const updateRemote = (patch: Partial<PluginSource>) => {
    if (remote) {
      onChange({...value, sources: [{...remote, ...patch}]});
    }
  };
  return <Section title={uiText("工具配置")}>
    <label className={ui.field}><span>{uiText("工具来源")}</span><Select className={ui.select}
                                                                         value={remote ? "mcp" : "collection"}
                                                                         disabled={readOnly} onChange={(event) => {
      const next = event.target.value as "collection" | "mcp";
      if (value.tools.length || remote?.endpoint || remote?.credentialId) {
        setSwitching(next);
      } else {
        applyType(next);
      }
    }}>
      <option value="collection">{uiText("选择内置工具")}</option>
      <option value="mcp">{uiText("连接远程服务")}</option>
    </Select></label>
    {remote ? <>
      <TextField label={uiText("MCP 服务地址")} name="config.sources[0].endpoint" value={remote.endpoint ?? ""} required
                 maximum={2048} errors={errors} onChange={(endpoint) => updateRemote({endpoint})}/>
      <label className={ui.field}><span>{uiText("连接方式")}</span><Select className={ui.select}
                                                                           value={remote.transport ?? "streamable_http"}
                                                                           disabled={readOnly}
                                                                           onChange={(event) => updateRemote({transport: event.target.value as PluginSource["transport"]})}>
        <option value="streamable_http">{uiText("流式 HTTP 连接")}</option>
        <option value="legacy_sse">{uiText("SSE 事件流连接")}</option>
      </Select></label>
      <CredentialPicker enterpriseId={enterpriseId} value={remote.credentialId ?? null}
                        onChange={(credentialId) => updateRemote({credentialId})}
                        canManage={permissions.includes("credential.manage")}
                        canUse={permissions.includes("credential.use") || permissions.includes("credential.manage")}
                        readOnly={readOnly}/>
    </> : <PluginCollectionTools enterpriseId={enterpriseId} value={value} onChange={onChange} readOnly={readOnly}
                                 allowed={permissions.includes("plugin.invoke")}/>}
    <NumberField label={uiText("工具等待上限（秒）")} name="config.timeoutSeconds" value={value.timeoutSeconds} min={1}
                 max={120} errors={errors} onChange={(timeoutSeconds) => onChange({...value, timeoutSeconds})}/>
    {switching && <Dialog title={uiText("切换工具来源？")} onClose={() => setSwitching(null)}><p
      className={ui.description}>{uiText("当前工具选择和连接信息将清空。")}</p><DialogActions className={ui.footer}>
      <DialogCancel className={ui.button}>{uiText("取消")}</DialogCancel><DialogAction className={ui.primary}
                                                                                       onAction={(close) => close(() => applyType(switching))}>{uiText("切换来源")}</DialogAction></DialogActions></Dialog>}
  </Section>;
}
