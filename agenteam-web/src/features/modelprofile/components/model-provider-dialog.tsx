"use client";

import {useT} from "@/lib/i18n/locale-provider";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Checkbox} from "@/components/ui/checkbox";

import {useId, useState} from "react";
import {Dialog, DialogCancel} from "@/components/ui/dialog";
import {Toggle} from "@/components/ui/toggle";
import {useConfirmClose} from "@/features/workspace/components/use-confirm-close";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {organizationPath} from "@/features/enterprise/api/organization-api";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {ModelProvider} from "../types/model-management";
import ui from "@/components/ui/surface.module.css";
import styles from "./model-management.module.css";

export function ModelProviderDialog({enterpriseId, provider, onClose, onSaved}: {
  enterpriseId: string; provider: ModelProvider | null; onClose: () => void; onSaved: () => void;
}) {
  const uiText = useT();
  const action = useFormAction();
  const [name, setName] = useState(provider?.name ?? "");
  const [baseUrl, setBaseUrl] = useState(provider?.baseUrl ?? "");
  const [apiKey, setApiKey] = useState("");
  const [clearKey, setClearKey] = useState(false);
  const [enabled, setEnabled] = useState(provider?.enabled ?? true);
  const formId = useId();
  const dirty = name !== (provider?.name ?? "") || baseUrl !== (provider?.baseUrl ?? "") || Boolean(apiKey) || clearKey || enabled !== (provider?.enabled ?? true);
  const closing = useConfirmClose(dirty, action.busy, onClose);
  return <><Dialog title={provider ? uiText("配置模型连接") : uiText("新增模型提供方")} onClose={onClose}
                   dialogRef={closing.dialogRef} onRequestClose={closing.canClose} busy={action.busy}
                   footer={<><DialogCancel className={ui.button}
                                           disabled={action.busy}>{uiText("取消")}</DialogCancel><Button form={formId}
                                                                                                         type="submit"
                                                                                                         className={ui.primary}
                                                                                                         disabled={action.busy}>{action.busy ? uiText("正在保存…") : uiText("保存连接")}</Button></>}>
    <form id={formId} className={ui.form} onSubmit={(event) => {
      event.preventDefault();
      void action.execute(async () => {
        await action.mutation.run(organizationPath(enterpriseId, provider ? `/model-providers/${encodeURIComponent(provider.id)}` : "/model-providers"), {
          method: provider ? "PUT" : "POST", revision: provider?.revision,
          body: {name, protocol: "openai", baseUrl, apiKey: clearKey ? "" : apiKey || null, enabled},
        });
        setApiKey("");
        closing.finish(onSaved);
      }, "");
    }}>
      <label className={ui.field}><span>{uiText("提供方名称")}</span><Input className={ui.input} value={name} required
                                                                            maxLength={80}
                                                                            disabled={action.busy} autoFocus
                                                                            onChange={(event) => setName(event.target.value)}/></label>
      <label className={ui.field}><span>{uiText("服务地址")}</span><Input className={ui.input} type="url"
                                                                          value={baseUrl} required maxLength={2048}
                                                                          placeholder="https://api.example.com/v1"
                                                                          disabled={action.busy || provider?.inUse}
                                                                          onChange={(event) => setBaseUrl(event.target.value)}/><span
        className={ui.description}>{uiText("使用兼容 OpenAI 的模型服务地址。")}</span></label>
      {provider?.inUse &&
        <p className={ui.description}>{uiText("该提供方已有模型被使用。如需更换服务地址，请新增提供方。")}</p>}
      <label
        className={ui.field}><span>{provider?.keyConfigured ? uiText("更新访问密钥") : uiText("访问密钥")}</span><Input
        className={ui.input} type="password"
        value={apiKey} maxLength={16384} autoComplete="new-password" disabled={action.busy || clearKey}
        onChange={(event) => setApiKey(event.target.value)}/>
        <span
          className={ui.description}>{provider?.keyConfigured ? uiText("留空保留已保存的密钥。") : uiText("无需身份验证的模型服务可以留空。")}</span></label>
      {provider?.keyConfigured && <label className={styles.check}><Checkbox checked={clearKey} disabled={action.busy}
                                                                            onCheckedChange={(checked) => setClearKey(checked)}/>{uiText("清除已保存的密钥")}
      </label>}
      <Toggle label={uiText("启用此提供方")} checked={enabled} disabled={action.busy} onChange={setEnabled}/>
      <MutationFeedback action={action}/>
    </form>
  </Dialog>{closing.confirmation}</>;
}
