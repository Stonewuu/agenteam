"use client";

import {useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {DetailHeading, SettingRow} from "@/components/ui/detail-section";
import {IconPlug, IconKey, IconBell, IconBuilding} from "@/components/ui/icons";
import {ChannelIdentity} from "./channel-identity";
import {Dialog, DialogActions, DialogCancel, DialogForm, useDialogControl} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Field} from "@/components/ui/field";
import {Select} from "@/components/ui/select";
import {QueryState} from "@/components/ui/query-state";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useT} from "@/lib/i18n/locale-provider";
import {useApiQuery} from "@/lib/http/use-api-query";
import type {Integration, IntegrationProvider} from "../types/integration";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";

export function IntegrationEditor({path, initial, mode, onClose, onSaved}: {
  path: string;
  initial: Integration | null;
  mode: "create" | "edit" | "secret";
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const action = useFormAction();
  const dialog = useDialogControl();
  const [providerCode, setProviderCode] = useState(initial?.providerCode ?? "wecom");
  const [name, setName] = useState(initial?.name ?? "");
  const [tenant, setTenant] = useState(initial?.externalTenantId ?? "");
  const [appId, setAppId] = useState(initial?.externalAppId ?? "");
  const [secret, setSecret] = useState("");
  const [configuration, setConfiguration] = useState<Record<string, string>>(
    Object.fromEntries(Object.entries(initial?.configuration ?? {}).map(([key, value]) => [key, String(value)])));
  const catalog = useApiQuery<IntegrationProvider[]>(mode === "secret" ? null : path.replace(/\/integrations$/, "/integration-providers"));
  const provider = catalog.data?.find((value) => value.code === providerCode);
  const [bindingEnabled, setBindingEnabled] = useState(initial?.bindingEnabled ?? true);
  const [loginEnabled, setLoginEnabled] = useState(initial?.loginEnabled ?? false);
  const [messagingEnabled, setMessagingEnabled] = useState(initial?.messagingEnabled ?? true);
  const creating = mode === "create";
  const title = creating ? t("新增企业接入") : mode === "secret" ? t("更换应用密钥") : t("编辑企业接入");

  return <Dialog title={title} icon={mode === "secret" ? <IconKey size={21}/> : <IconPlug size={21}/>} onClose={onClose} dialogRef={dialog.ref} busy={action.busy}>
    <DialogForm className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        const publicConfiguration = Object.fromEntries((provider?.fields ?? []).filter((field) => field.name.startsWith("configuration.")).map((field) => {
          const key = field.name.slice("configuration.".length);
          const value = configuration[key] ?? String(field.defaultValue ?? "");
          return [key, field.type === "number" ? Number(value) : value];
        }));
        if (creating) {
          await action.mutation.run(path, {method: "POST", body: {providerCode, name, externalAppId: appId, secret,
            bindingEnabled, loginEnabled, messagingEnabled,
            configuration: publicConfiguration,
            ...(provider?.fields.some((field) => field.name === "externalTenantId") ? {externalTenantId: tenant} : {})}});
        } else if (mode === "secret" && initial) {
          await action.mutation.run(`${path}/${encodeURIComponent(initial.id)}/rotate-secret`, {
            method: "POST", revision: initial.revision, body: {secret}
          });
        } else if (initial) {
          await action.mutation.run(`${path}/${encodeURIComponent(initial.id)}`, {
            method: "PATCH", revision: initial.revision, body: {name, bindingEnabled, loginEnabled, messagingEnabled,
              configuration: publicConfiguration}
          });
        }
        setSecret("");
        dialog.close(onSaved);
      }, t("接入配置已保存。"));
    }}>
      {initial && <ChannelIdentity name={initial.name} providerName={initial.providerName} providerCode={initial.providerCode}/>}
      {creating && <DetailHeading title={t("应用信息")} icon={<IconBuilding size={18}/>}/>}
      {creating && <Field label={t("平台")} error={action.fieldErrors.providerCode?.join(" ")}>
        <Select name="providerCode" value={providerCode} disabled={action.busy} onChange={(event) => {
          setProviderCode(event.target.value);
          setAppId("");
          setTenant("");
          setSecret("");
          setConfiguration({});
        }}>{catalog.data?.map((value) => <option key={value.code} value={value.code}>{value.name}</option>)}</Select>
      </Field>}
      {mode !== "secret" && <Field label={t("接入名称")} required error={action.fieldErrors.name?.join(" ")}>
        <Input name="name" required maxLength={80} value={name} disabled={action.busy}
               onChange={(event) => setName(event.target.value)}/></Field>}
      {mode !== "secret" && <QueryState {...catalog} hasData={Boolean(provider)} empty={t("此接入平台暂不可用。")}>
        <AnimatedHeight preserveControlShadows><div className={ui.form}>
          {provider?.fields.filter((field) => creating || field.name.startsWith("configuration.")).map((field) => {
            const key = field.name.slice("configuration.".length);
            const value = field.name === "externalTenantId" ? tenant : field.name === "externalAppId" ? appId
              : configuration[key] ?? String(field.defaultValue ?? "");
            return <Field key={`${provider.code}:${field.name}`} label={t(field.label)} required={field.required}
              hint={field.hint ? t(field.hint) : undefined} error={action.fieldErrors[field.name]?.join(" ")}>
              <Input name={field.name} type={field.type} required={field.required} value={value} disabled={action.busy}
                min={field.type === "number" ? field.minimum ?? undefined : undefined}
                max={field.type === "number" ? field.maximum ?? undefined : undefined}
                maxLength={field.type === "text" ? field.maximum ?? 191 : undefined}
                step={field.type === "number" ? 1 : undefined} autoComplete="off" onChange={(event) => {
                  if (field.name === "externalTenantId") {
                    setTenant(event.target.value);
                  } else if (field.name === "externalAppId") {
                    setAppId(event.target.value);
                  } else {
                    setConfiguration((current) => ({...current, [key]: event.target.value}));
                  }
                }}/>
            </Field>;
          })}
        </div></AnimatedHeight>
      </QueryState>}
      {(creating || mode === "secret") && <Field label={t("应用密钥（Secret）")} required error={action.fieldErrors.secret?.join(" ")}>
        <Input name="secret" type="password" required maxLength={8192} value={secret} disabled={action.busy}
               autoComplete="new-password" onChange={(event) => setSecret(event.target.value)}/></Field>}
      {mode === "secret" && <p className={ui.description}>{t("更换后需重新校验，再启用接入。")}</p>}
      {mode !== "secret" && <div className={styles.editorOptions}>
        <DetailHeading title={t("成员可用功能")} icon={<IconPlug size={18}/>}/>
        <SettingRow icon={<IconPlug size={18}/>} label={t("允许成员绑定账号")} description={t("成员可关联当前企业的渠道账号。")}
          checked={bindingEnabled} disabled={action.busy} onChange={setBindingEnabled}/>
        <SettingRow icon={<IconKey size={18}/>} label={t("允许已绑定成员登录")} description={t("成员可通过此企业账号登录。")}
          checked={loginEnabled} disabled={action.busy} onChange={setLoginEnabled}/>
        <SettingRow icon={<IconBell size={18}/>} label={t("允许发送通知")} description={t("向已绑定且允许接收的成员发送工作通知。")}
          checked={messagingEnabled} disabled={action.busy} onChange={setMessagingEnabled}/>
      </div>}
      <MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button className={ui.primary} disabled={action.busy || mode !== "secret" && !provider}>{action.busy ? t("正在保存…") : t("保存")}</Button>
      </DialogActions>
    </DialogForm>
  </Dialog>;
}
