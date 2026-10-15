"use client";

import {useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Textarea} from "@/components/ui/textarea";
import {Select} from "@/components/ui/select";
import {IconRobot} from "@/components/ui/icons";
import {DetailHeading} from "@/components/ui/detail-section";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import type {Integration} from "@/features/integration/types/integration";
import {useChatBotConnection} from "../hooks/use-chat-bot-connection";
import {saveChatBot} from "../api/save-chat-bot";
import {ChatBotConnectionFields} from "./chat-bot-connection-fields";
import {ChatAgentPicker, ChatAgentVersionPicker} from "./chat-agent-picker";
import {ChatAdvancedSettings} from "./chat-advanced-settings";
import {useT} from "@/lib/i18n/locale-provider";
import {chatPath, type ChatBot} from "../types/chat";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatBotEditor({enterpriseId, system, initial, connection, canTest = true, onClose, onSaved}: {
  enterpriseId: string; system: boolean; initial: ChatBot | null; connection?: Integration; canTest?: boolean; onClose: () => void; onSaved: () => void;
}) {
  const t = useT();
  const action = useFormAction();
  const path = chatPath(enterpriseId, system);
  const binding = useChatBotConnection({enterpriseId, system, initial, connection, canTest});
  const created = useRef<ChatBot | null>(null);
  const [selected, setSelected] = useState<string[]>(initial?.agentVersionId ? [initial.agentVersionId] : []);
  const [agentId, setAgentId] = useState(initial?.agentId ?? "");
  const [agentError, setAgentError] = useState("");
  const [roleName, setName] = useState(initial?.roleName ?? connection?.name ?? "");
  const automaticName = useRef("");
  const [roleDescription, setDescription] = useState(initial?.roleDescription ?? "");
  const [versionPolicy, setVersionPolicy] = useState(initial?.versionPolicy ?? "latest_on_new_task");
  const [transport, setTransport] = useState(initial?.transport ?? "websocket");
  const [advanced, setAdvanced] = useState(false);
  const [verificationToken, setToken] = useState("");
  const [encryptKey, setKey] = useState("");
  const provider = binding.provider;
  const webhook = provider === "feishu" && transport === "webhook";
  const selectAgent = (agent: string, version: string, name?: string) => {
    setAgentError("");
    action.clearFieldError("agentId");
    setAgentId(agent);
    setSelected([version]);
    if (name && (!roleName.trim() || roleName === automaticName.current)) {
      setName(name);
      automaticName.current = name;
    }
  };
  return <Dialog title={t(initial ? "机器人设置" : "添加岗位机器人")} icon={<IconRobot size={22}/>} busy={action.busy} onClose={onClose} wide>
    <DialogForm className={styles.fields} onInvalidCapture={() => {
      if (!agentId) {
        setAgentError(t("请选择关联智能体。"));
      }
    }} onSubmit={(event) => {
      event.preventDefault();
      if (!agentId || versionPolicy === "pinned" && !selected[0]) {
        setAdvanced(versionPolicy === "pinned");
        setAgentError(t(agentId ? "请选择智能体版本。" : "请选择关联智能体。"));
        event.currentTarget.querySelector<HTMLElement>('[data-agent-picker] button[role="combobox"]')?.focus();
        return;
      }
      if (!roleName.trim()) {
        action.setError(t("请填写岗位名称。"));
        return;
      }
      const needsCallback = webhook && (!(created.current ?? initial)?.callbackConfigured || Boolean(verificationToken || encryptKey));
      if (needsCallback && (!verificationToken || !encryptKey)) {
        setAdvanced(true);
        action.setError(t("请填写回调校验令牌和加密密钥。"));
        return;
      }
      void action.execute(async () => {
        const connectionId = await binding.ensure(action.mutation, roleName);
        const fields = {connectionId, agentId, versionPolicy, pinnedVersionId: versionPolicy === "pinned" ? selected[0] : null,
          roleName, roleDescription, transport: provider === "wecom_aibot" ? "websocket" : transport};
        await saveChatBot(action.mutation, path, initial, created, fields, needsCallback ? {verificationToken, encryptKey} : null, () => {
          setToken("");
          setKey("");
        });
        onSaved();
      }, t(initial ? "岗位机器人已保存。" : "机器人已添加并启用。"));
    }}>
      {!initial && <ChatBotConnectionFields connection={binding} disabled={action.busy} errors={action.fieldErrors} canTest={canTest}/>}
      {!initial && <DetailHeading title={t("安排岗位工作")} icon={<IconRobot size={18}/>} description={t("选择负责处理消息的智能体，岗位名称将显示在群策任务中。")}/>}
      <div className={styles.limits}><ChatAgentPicker path={path} agentId={agentId} versionId={selected[0] ?? ""} disabled={action.busy} onChange={selectAgent}
        error={agentError || action.fieldErrors.agentId?.join(" ")}/>
      <Field label={t("岗位名称")} required error={action.fieldErrors.roleName?.join(" ")}><Input name="roleName" value={roleName} maxLength={80} required disabled={action.busy} onChange={(event) => setName(event.target.value)}/></Field></div>
      <Field label={t("岗位职责")} error={action.fieldErrors.roleDescription?.join(" ")}><Textarea name="roleDescription" value={roleDescription} maxLength={500} disabled={action.busy} onChange={(event) => setDescription(event.target.value)}/></Field>
      <ChatAdvancedSettings open={advanced} onOpenChange={setAdvanced}>
        <Field label={t("版本选择")}><Select value={versionPolicy} disabled={action.busy} onChange={(event) => setVersionPolicy(event.target.value as ChatBot["versionPolicy"])}>
          <option value="latest_on_new_task">{t("新任务使用最新发布版本")}</option><option value="pinned">{t("使用选定版本")}</option>
        </Select></Field>
        {versionPolicy === "pinned" && agentId && <ChatAgentVersionPicker path={path} agentId={agentId} versionId={selected[0] ?? ""} disabled={action.busy} onChange={selectAgent}/>}
        {provider === "feishu" && <Field label={t("消息接收方式")}><Select value={transport} disabled={action.busy} onChange={(event) => setTransport(event.target.value as ChatBot["transport"])}>
          <option value="websocket">{t("长连接")}</option><option value="webhook">{t("平台回调")}</option>
        </Select></Field>}
        {webhook && <>
          {initial?.callbackPath && <Field label={t("回调路径")}><Input value={initial.callbackPath} readOnly/></Field>}
          <Field label={t("校验令牌（Verification Token）")} required={!initial?.callbackConfigured}><Input type="password" value={verificationToken} maxLength={8192} autoComplete="new-password" disabled={action.busy} onChange={(event) => setToken(event.target.value)}/></Field>
          <Field label={t("加密密钥（Encrypt Key）")} required={!initial?.callbackConfigured}><Input type="password" value={encryptKey} maxLength={8192} autoComplete="new-password" disabled={action.busy} onChange={(event) => setKey(event.target.value)}/></Field>
        </>}
      </ChatAdvancedSettings>
      <MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel><Button className={ui.primary} disabled={action.busy}>{t(action.busy ? "正在保存…" : initial ? "保存" : "添加并启用")}</Button></DialogActions>
    </DialogForm>
  </Dialog>;
}
