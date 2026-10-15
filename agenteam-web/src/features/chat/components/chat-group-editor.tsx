"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Checkbox} from "@/components/ui/checkbox";
import {Dialog, DialogActions, DialogCancel, DialogForm} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Input} from "@/components/ui/input";
import {Select} from "@/components/ui/select";
import {IconMessages} from "@/components/ui/icons";
import {Guidance} from "@/components/ui/guidance";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useT} from "@/lib/i18n/locale-provider";
import type {ChatGroup} from "../types/chat";
import {ChatAdvancedSettings} from "./chat-advanced-settings";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

const limits = [
  {key: "maxWorks", label: "最多岗位工作", value: 20, min: 1, max: 100},
  {key: "maxHandoffs", label: "最多交接次数", value: 8, min: 0, max: 20},
  {key: "maxRuns", label: "最多执行轮次", value: 40, min: 1, max: 200},
  {key: "maxParallel", label: "同时工作岗位", value: 2, min: 1, max: 4},
  {key: "deadlineMinutes", label: "任务最长时间（分钟）", value: 1440, min: 10, max: 10080},
  {key: "newTasksPerMinute", label: "每人每分钟新任务", value: 5, min: 1, max: 20},
];

export function ChatGroupEditor({path, value, onClose, onSaved}: {path: string; value: ChatGroup; onClose: () => void; onSaved: (group: ChatGroup) => void}) {
  const t = useT();
  const action = useFormAction();
  const available = value.bots.filter((bot) => bot.membershipStatus === "present");
  const [selected, setSelected] = useState(available.filter((bot) => bot.collaborationEnabled).map((bot) => bot.botId));
  const [selectionError, setSelectionError] = useState("");
  const [entry, setEntry] = useState(value.ingressBotId ?? "");
  const [advanced, setAdvanced] = useState(false);
  const [mode, setMode] = useState(value.ingressMode);
  const [policy, setPolicy] = useState(value.resultPolicy);
  const canReceiveTogether = selected.length >= 2 && selected.every((id) => available.find((bot) => bot.botId === id)?.crossBotIdentityVerified);
  const [maximum, setMaximum] = useState<Record<string, number>>(() => Object.fromEntries(limits.map((limit) => [limit.key,
    value.limits?.[limit.key] ?? (limit.key === "deadlineMinutes" && value.limits?.deadlineHours ? value.limits.deadlineHours * 60 : limit.value)])));
  return <Dialog title={value.name ?? t(value.kind === "direct" ? "机器人私聊" : "群聊设置")} icon={<IconMessages size={22}/>} onClose={onClose} busy={action.busy} wide>
    <DialogForm className={styles.fields} onSubmit={(event) => {
      event.preventDefault();
      if (!selected.length) {
        setSelectionError(t("请选择至少一个参与岗位。"));
        event.currentTarget.querySelector<HTMLElement>('[role="checkbox"]')?.focus();
        return;
      }
      if (mode === "multi_bot" && !canReceiveTogether) {
        setAdvanced(true);
        action.setError(t("请先在机器人“连接检查”中完成共同接收测试，或选择“通过指定机器人接收”。"));
        return;
      }
      void action.execute(async () => {
        const updated = await action.mutation.run<ChatGroup>(`${path}/conversations/${value.id}`, {method: "PATCH", body: {
          expectedRevision: value.revision, status: "enabled", ingressMode: mode,
          ingressBotId: selected.includes(entry) ? entry : selected[0], defaultBotId: selected.includes(entry) ? entry : selected[0],
          triggerMode: "mention", resultPolicy: policy, allowedBotIds: selected, limits: maximum,
        }});
        onSaved(updated);
      }, t("参与岗位已保存，群聊已启用。"));
    }}>
      <Guidance title={t(mode === "multi_bot" ? "分别 @ 所需岗位即可发起任务" : "通过指定机器人发起任务")} icon={<IconMessages size={19}/>}>
        <p>{t(mode === "multi_bot" ? "在群里通过 @ 菜单选择参与岗位的机器人；需要多个岗位协作时，可以在同一条消息中说明分工。"
          : "在群里 @ 下方选定的机器人并描述任务；它可以将工作委派给其他已选岗位。")}</p>
      </Guidance>
      <Field label={t("参与岗位")} error={selectionError || action.fieldErrors.allowedBotIds?.join(" ")}>
        <div>{available.map((bot) => <label className={styles.choice} key={bot.botId}><Checkbox checked={selected.includes(bot.botId)} disabled={action.busy}
          onCheckedChange={(checked) => {
            setSelectionError("");
            action.clearFieldError("allowedBotIds");
            setSelected((current) => checked ? [...current, bot.botId] : current.filter((id) => id !== bot.botId));
          }}/>{bot.roleName}</label>)}</div>
      </Field>
      {selected.length > 1 && <Field label={t(mode === "multi_bot" ? "默认负责岗位" : "接收任务的机器人")}
        hint={mode === "multi_bot" ? t("同时 @ 此岗位和其他岗位时，先由此岗位接收并安排工作。") : undefined}
        required error={action.fieldErrors.ingressBotId?.join(" ")}><Select required value={selected.includes(entry) ? entry : selected[0]} disabled={action.busy} onChange={(event) => setEntry(event.target.value)}>
        <option value="">{t("请选择")}</option>{available.filter((bot) => selected.includes(bot.botId)).map((bot) => <option key={bot.botId} value={bot.botId}>{bot.roleName}</option>)}
      </Select></Field>}
      <Field label={t("群内回复限制")}><Select value={policy} disabled={action.busy} onChange={(event) => setPolicy(event.target.value as ChatGroup["resultPolicy"])}>
        <option value="full_allowed">{t("允许成员自行选择")}</option><option value="summary_link">{t("仅允许链接回复")}</option>
      </Select></Field>
      <ChatAdvancedSettings open={advanced} onOpenChange={setAdvanced}>
        {value.platformCode === "feishu" && <Field label={t("任务接收方式")}><Select value={mode} disabled={action.busy} onChange={(event) => setMode(event.target.value as ChatGroup["ingressMode"])}>
          <option value="single_bot">{t("通过指定机器人接收")}</option><option value="multi_bot" disabled={!canReceiveTogether}>{t("通过多个岗位机器人接收")}</option>
        </Select></Field>}
        {value.platformCode === "feishu" && !canReceiveTogether && <p className={styles.muted}>{t("如需分别 @ 多个机器人，请先选择两个以上岗位，并在机器人“连接检查”中完成共同接收测试。")}</p>}
        <div className={styles.limits}>{limits.map((limit) => <Field key={limit.key} label={t(limit.label)}><Input type="number" value={maximum[limit.key]} required
        min={limit.min} max={limit.max} step={1} disabled={action.busy} onChange={(event) => setMaximum((current) => ({...current, [limit.key]: Number(event.target.value)}))}/></Field>)}</div>
      </ChatAdvancedSettings>
      <MutationFeedback action={action}/><DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel><Button className={ui.primary} disabled={action.busy || !available.length}>{t(value.status === "enabled" ? "保存" : "保存并启用")}</Button></DialogActions>
    </DialogForm>
  </Dialog>;
}
