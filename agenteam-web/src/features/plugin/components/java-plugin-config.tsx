"use client";

import {useState} from "react";
import {useT} from "@/lib/i18n/locale-provider";
import {Button} from "@/components/ui/button";
import {Select} from "@/components/ui/select";
import {Checkbox} from "@/components/ui/checkbox";
import {FilePicker} from "@/components/ui/file-picker";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {ApiError, apiRequest, errorMessage, reportRequestFailure} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {PluginConfig, PluginSource} from "@/features/resource/types/resource";
import {CredentialPicker} from "./credential-picker";
import {JavaPluginFields} from "./java-plugin-fields";
import type {JavaPlugin, JavaPluginCatalog} from "../types/java-plugin";
import styles from "./java-plugin.module.css";
import ui from "@/components/ui/surface.module.css";

export function JavaPluginConfig({enterpriseId, value, onChange, readOnly, permissions}: {
  enterpriseId: string;
  value: PluginConfig;
  onChange: (value: PluginConfig) => void;
  readOnly: boolean;
  permissions: string[];
}) {
  const t = useT();
  const base = `/api/v1/enterprises/${enterpriseId}/plugins/java-packages`;
  const selected = value.sources.find((source) => source.type === "java");
  const [refresh, setRefresh] = useState(0);
  const query = useApiQuery<JavaPluginCatalog>(`${base}${selected?.versionId ? `?include=${encodeURIComponent(selected.versionId)}` : ""}`, refresh);
  const plugin = query.data?.items.find((item) => item.id === selected?.versionId);
  const [pending, setPending] = useState<{action: "install" | "uninstall" | "select"; plugin: JavaPlugin} | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const update = (patch: Partial<PluginSource>) => {
    if (selected) {
      onChange({...value, sources: [{...selected, ...patch}]});
    }
  };
  const choose = (next: JavaPlugin) => {
    const configuration: Record<string, string | number | boolean> = {};
    for (const [name, field] of Object.entries(next.configurationSchema.properties)) {
      if (field.default !== undefined) {
        configuration[name] = field.default;
      } else if (field.type === "boolean") {
        configuration[name] = false;
      }
    }
    onChange({...value, sources: [{id: next.pluginId, type: "java", versionId: next.id, configuration, credentialId: null}], tools: []});
  };
  const upload = async (file: File) => {
    setError("");
    if (!file.name.toLowerCase().endsWith(".jar") || file.size > 20 * 1024 * 1024) {
      setError(t("请选择不超过 20 MiB 的插件 JAR 文件。"));
      return;
    }
    setBusy(true);
    try {
      const prepared = await apiRequest<JavaPlugin>(`${base}/content`, {method: "PUT", rawBody: file, requestKey: crypto.randomUUID(), timeoutMs: 45000});
      setPending({action: "install", plugin: prepared});
    } catch (failure) {
      reportRequestFailure(failure, "PUT", `${base}/content`);
      setError(failure instanceof ApiError ? errorMessage(failure) : t("插件配置未能更新，请重新读取后再试。"));
    } finally {
      setBusy(false);
    }
  };
  const confirm = async () => {
    if (!pending) {
      return;
    }
    setBusy(true);
    setError("");
    try {
      if (pending.action === "select") {
        choose(pending.plugin);
      } else if (pending.action === "install") {
        await apiRequest(`${base}/${pending.plugin.id}/activate`, {method: "POST", body: {previousInstallationId: pending.plugin.currentInstallationId}});
      } else {
        await apiRequest(`${base}/${pending.plugin.id}`, {method: "DELETE"});
      }
      setPending(null);
      setRefresh((current) => current + 1);
    } catch (failure) {
      reportRequestFailure(failure, "POST", base);
      setError(failure instanceof ApiError ? errorMessage(failure) : t("插件配置未能更新，请重新读取后再试。"));
    } finally {
      setBusy(false);
    }
  };
  const closePending = () => {
    const cancelled = pending;
    setPending(null);
    setError("");
    if (cancelled?.action === "install") {
      void apiRequest(`${base}/${cancelled.plugin.id}`, {method: "DELETE"}).catch((failure) => setError(errorMessage(failure)));
    }
  };
  return <div className={styles.root}>
    <div className={styles.actions}>
      {query.data?.canManage && !readOnly && <FilePicker accept=".jar" label={t("上传插件包")} disabled={busy}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            void upload(file);
          }
          event.target.value = "";
        }}/>
      }
      <Button type="button" className={ui.button} disabled={busy} onClick={() => setRefresh((current) => current + 1)}>{t("刷新")}</Button>
    </div>
    {busy && !pending && <p role="status" className={ui.description}>{t("正在检查插件包…")}</p>}
    {!pending && error && <p role="alert" className={ui.error}>{error}</p>}
    <QueryState loading={query.loading} error={query.error} retry={query.retry} empty={t("暂无已安装的扩展插件。")}
      hasData={Boolean(query.data)}>
      <label className={ui.field}><span>{t("插件版本")}</span><Select className={ui.select}
        value={selected?.versionId ?? ""} disabled={readOnly || busy} onChange={(event) => {
          const next = query.data?.items.find((item) => item.id === event.target.value);
          if (next) {
            setPending({action: "select", plugin: next});
          }
        }}>
        <option value="">{t("请选择插件")}</option>
        {query.data?.items.map((item) => <option key={item.id} value={item.id} disabled={item.status !== "active"}>
          {item.name} · {item.version}{item.status !== "active" ? `（${t(item.currentInstallationId ? "有更新" : "已下架")}）` : ""}
        </option>)}
      </Select></label>
      <AnimatedHeight preserveControlShadows>
        {plugin && <div className={styles.detail}>
          <div><strong>{plugin.name}</strong><p className={ui.description}>{plugin.description}</p><small>{t("作者")}：{plugin.author}</small></div>
          {plugin.status !== "active" ? <div className={styles.notice} role="status">
            <p>{t(plugin.currentInstallationId ? "插件有更新，请确认后重新选择版本。" : "插件已下架，请移除。")}</p>
            {!readOnly && <Button type="button" className={ui.button} onClick={() => onChange({...value, sources: [], tools: []})}>{t("移除")}</Button>}
          </div> : <>
            <JavaPluginFields plugin={plugin} value={selected?.configuration ?? {}} disabled={readOnly || busy}
              onChange={(configuration) => update({configuration})}/>
            <CredentialPicker enterpriseId={enterpriseId} value={selected?.credentialId ?? null}
              onChange={(credentialId) => update({credentialId})} canManage={permissions.includes("credential.manage")}
              canUse={permissions.includes("credential.use") || permissions.includes("credential.manage")} readOnly={readOnly}/>
            <div className={styles.tools}><strong>{t("启用的工具")}</strong>{plugin.tools.map((tool) => <label key={tool.name} className={styles.tool}>
              <Checkbox disabled={readOnly || busy} checked={value.tools.some((selection) => selection.name === tool.name)}
                onCheckedChange={(checked) => onChange({...value, tools: checked
                  ? [...value.tools.filter((selection) => selection.name !== tool.name), {id: crypto.randomUUID(), sourceId: plugin.pluginId, name: tool.name}]
                  : value.tools.filter((selection) => selection.name !== tool.name)})}/>
              <span><strong>{tool.annotations.title ?? tool.name}</strong><small>{tool.description}</small></span>
            </label>)}</div>
            {query.data?.canManage && !readOnly && <div><Button type="button" className={ui.button} disabled={busy}
              onClick={() => setPending({action: "uninstall", plugin})}>{t("卸载插件")}</Button></div>}
          </>}
        </div>}
      </AnimatedHeight>
    </QueryState>
    {pending && <Dialog title={t(pending.action === "install" ? "安装插件" : pending.action === "uninstall" ? "卸载插件" : "选择插件版本")}
      busy={busy} onClose={closePending}>
      <div className={styles.detail}><strong>{pending.plugin.name} · {pending.plugin.version}</strong>
        <p>{pending.plugin.description}</p>
        {pending.action === "install" && <><p>{t("作者")}：{pending.plugin.author}</p>
          <div className={styles.tools}>{pending.plugin.tools.map((tool) => <p key={tool.name}>{tool.annotations.title ?? tool.name}：{tool.description}</p>)}</div>
          {pending.plugin.currentInstallationId && <p>{t("安装后旧版本立即失效，正在进行的旧插件调用将停止。已有配置需要重新选择版本。")}</p>}</>}
        {pending.action === "uninstall" && <p>{t("卸载后将停止此版本的调用。引用它的配置会保留提示，后续运行不再使用此插件。")}</p>}
        {pending.action === "select" && <p>{t("选择后请填写配置并勾选需要的工具，保存并发布后生效。")}</p>}
        {error && <p role="alert" className={ui.error}>{error}</p>}
      </div>
      <DialogActions><DialogCancel className={ui.button} disabled={busy}>{t("取消")}</DialogCancel>
        <Button type="button" className={ui.primary} disabled={busy} onClick={() => void confirm()}>{t(busy ? "正在处理…" : "确认")}</Button>
      </DialogActions>
    </Dialog>}
  </div>;
}
