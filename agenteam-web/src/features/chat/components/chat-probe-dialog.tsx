"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {Field} from "@/components/ui/field";
import {Select} from "@/components/ui/select";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {IconMessages} from "@/components/ui/icons";
import {Guidance} from "@/components/ui/guidance";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiPage} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import type {ChatBot, ChatGroup} from "../types/chat";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatProbeDialog({path, bot, onClose, onChanged}: {path: string; bot: ChatBot; onClose: () => void; onChanged: () => void}) {
  const t = useT();
  const action = useFormAction();
  const groups = useApiPage<ChatGroup>(`${path}/conversations`);
  const [selected, setSelected] = useState("");
  const [probe, setProbe] = useState<{marker: string; expiresAt: string} | null>(null);
  const [queued, setQueued] = useState(false);
  const [cardQueued, setCardQueued] = useState(false);
  const [peer, setPeer] = useState("");
  const [sender, setSender] = useState("");
  const members = useApiPage<{bindingId: string; name: string; username: string}>(`${path}/bots/${bot.id}/test-members`);
  const choices = groups.data?.items.filter((group) => group.status === "enabled" && group.bots.some((member) => member.botId === bot.id && member.membershipStatus === "present")) ?? [];
  const peers = choices.find((group) => group.id === selected)?.bots.filter((member) => member.botId !== bot.id && member.membershipStatus === "present") ?? [];
  async function check(type: "credential" | "readHistory") {
    const result = await action.mutation.run<{status: string}>(`${path}/bots/${bot.id}/checks`, {method: "POST", body: {
      expectedRevision: bot.revision, type, ...(type === "readHistory" ? {conversationId: selected} : {}),
    }});
    onChanged();
    if (result.status !== "passed") {
      throw new Error(t(type === "credential" ? "机器人身份检查未通过，请在“连接设置”中检查应用凭据后重试。"
        : "未能读取群聊历史，请检查机器人在该群的权限后重试。"));
    }
  }
  return <Dialog title={t("测试收发消息")} icon={<IconMessages size={22}/>} onClose={onClose} busy={action.busy}>
    <div className={styles.panel}><p>{bot.roleName}</p>
      <QueryState {...groups} hasData={choices.length > 0} empty={t("请先将机器人加入群聊，再到“聊天会话”中选择参与岗位并启用。")}>
        <Field label={t("测试会话")}><Select value={selected} onChange={(event) => {
          setSelected(event.target.value);
          setProbe(null);
          setQueued(false);
          setCardQueued(false);
          setPeer("");
        }} disabled={action.busy}><option value="">{t("请选择")}</option>{choices.map((group) => <option key={group.id} value={group.id}>{group.name ?? t(group.kind === "direct" ? "机器人私聊" : "群聊")}</option>)}</Select></Field>
        <Pagination {...groups} hasMore={groups.data?.hasMore}/>
      </QueryState>
      <QueryState {...members} hasData={Boolean(members.data?.items.length)} empty={t("请参与测试的成员在“个人设置 → 企业账号绑定”绑定账号，并开启“允许通过此账号使用智能体”。")}>
        <Field label={t("发送测试文字的成员")}><Select value={sender} disabled={action.busy} onChange={(event) => {
          setSender(event.target.value);
          setProbe(null);
        }}><option value="">{t("请选择")}</option>{members.data?.items.map((member) => <option value={member.bindingId} key={member.bindingId}>{member.name} · {member.username}</option>)}</Select></Field>
        <Pagination {...members} hasMore={members.data?.hasMore}/>
      </QueryState>
      {bot.providerCode === "feishu" && peers.length > 0 && <Field label={t("一起测试的机器人（选填）")}><Select value={peer} disabled={action.busy} onChange={(event) => {
        setPeer(event.target.value);
        setProbe(null);
      }}>
        <option value="">{t("仅测试当前机器人")}</option>{peers.map((member) => <option value={member.botId} key={member.botId}>{member.roleName}</option>)}
      </Select></Field>}
      <div className={styles.actions}>
        <Button className={ui.button} disabled={action.busy} onClick={() => void action.execute(() => check("credential"), t("机器人身份检查通过。"))}>{t("检查机器人身份")}</Button>
        <Button className={ui.button} disabled={action.busy || !selected || !sender} onClick={() => void action.execute(async () => {
          setProbe(await action.mutation.run(`${path}/bots/${bot.id}/receive-probes`, {method: "POST", body: {expectedRevision: bot.revision, conversationId: selected, type: "text", senderBindingId: sender, peerBotId: peer || null}}));
        }, "")}>{t("获取测试文字")}</Button>
        <Button className={ui.button} disabled={action.busy || !selected} onClick={() => void action.execute(async () => {
          await action.mutation.run(`${path}/bots/${bot.id}/send-probes`, {method: "POST", body: {expectedRevision: bot.revision, conversationId: selected, type: "text"}});
          setQueued(true);
          onChanged();
        }, "")}>{t("向此会话发送测试消息")}</Button>
        <Button className={ui.button} disabled={action.busy || !selected} onClick={() => void action.execute(async () => {
          await action.mutation.run(`${path}/bots/${bot.id}/send-probes`, {method: "POST", body: {expectedRevision: bot.revision, conversationId: selected, type: "file"}});
          setQueued(true);
          onChanged();
        }, "")}>{t("向此会话发送测试文件")}</Button>
        {bot.providerCode === "feishu" && <Button className={ui.button} disabled={action.busy || !selected} onClick={() => void action.execute(() => check("readHistory"), t("群历史读取检查通过。"))}>{t("检查群历史读取")}</Button>}
        {bot.providerCode === "feishu" && <Button className={ui.button} disabled={action.busy || !selected} onClick={() => void action.execute(async () => {
          await action.mutation.run(`${path}/bots/${bot.id}/send-probes`, {method: "POST", body: {expectedRevision: bot.revision, conversationId: selected, type: "card"}});
          setCardQueued(true);
          onChanged();
        }, "")}>{t("向此会话发送测试卡片")}</Button>}
      </div>
      {probe && <Guidance title={t("下一步：在聊天中发送测试文字")} icon={<IconMessages size={19}/>}><p>{t(peer ? "请所选成员同时 @ 两个机器人，发送下面的测试文字。" : "请所选成员使用已绑定的平台账号，在所选会话中 @ 此机器人并发送下面的测试文字。")}</p><p className={styles.secret}>{probe.marker}</p>
        <p>{t("有效期至")} <EnterpriseDateTime value={probe.expiresAt}/></p></Guidance>}
      {queued && <Guidance title={t("已提交发送")} icon={<IconMessages size={19}/>}><p>{t("请到所选会话查看测试消息，发送结果可在“发送记录”中查看。")}</p></Guidance>}
      {cardQueued && <Guidance title={t("下一步：点击飞书中的卡片按钮")} icon={<IconMessages size={19}/>}><p>{t("请使用已绑定的飞书账号，在所选会话点击“确认卡片按钮”。")}</p><p>{t("如果点击无响应，请在飞书开放平台的“事件与回调 → 回调配置”中启用卡片回传交互，再重新发送测试卡片。")}</p></Guidance>}
      <MutationFeedback action={action}/><DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
    </div>
  </Dialog>;
}
