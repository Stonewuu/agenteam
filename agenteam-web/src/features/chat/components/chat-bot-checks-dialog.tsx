"use client";

import {useState} from "react";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {Button} from "@/components/ui/button";
import {DetailStatus} from "@/components/ui/detail-section";
import {IconMessages, IconShield} from "@/components/ui/icons";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useT} from "@/lib/i18n/locale-provider";
import {ChatProbeDialog} from "./chat-probe-dialog";
import type {ChatBot} from "../types/chat";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

const checks: Record<string, string> = {credential: "机器人身份", receiveMention: "接收 @ 消息", sendText: "发送文字", readHistory: "读取群历史", threadReply: "话题回复", uploadFile: "上传附件", crossBotIdentity: "多个机器人共同接收", cardInteraction: "卡片按钮"};
const labels: Record<string, string> = {passed: "检查通过", failed: "检查未通过", unsupported: "不支持", unavailable: "暂时不可用"};

export function ChatBotChecksDialog({path, bot, canTest, onClose, onChanged}: {path: string; bot: ChatBot; canTest: boolean; onClose: () => void; onChanged: () => void}) {
  const t = useT();
  const [testing, setTesting] = useState(false);
  return <Dialog title={t("连接检查")} icon={<IconShield size={22}/>} onClose={onClose}>
    <div className={styles.panel}><strong>{bot.roleName}</strong>
      <div className={styles.list}>{Object.entries(checks).filter(([code]) => code !== "cardInteraction" || bot.providerCode === "feishu").map(([code, label]) => <div className={styles.check} key={code}>
        <span>{t(label)}</span><DetailStatus tone={bot.capabilities[code]?.status === "passed" ? "success" : bot.capabilities[code]?.status === "failed" ? "danger" : "neutral"}>
          {t(labels[bot.capabilities[code]?.status] ?? "未检查")}
        </DetailStatus>
      </div>)}</div>
      {bot.lastConnectedAt && <p className={styles.muted}>{t("上次连接时间")} <EnterpriseDateTime value={bot.lastConnectedAt}/></p>}
    </div>
    <DialogActions>{canTest && <Button className={ui.primary} disabled={bot.status !== "enabled"} onClick={() => setTesting(true)}><IconMessages size={16}/>{t("测试收发消息")}</Button>}
      <DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
    {testing && <ChatProbeDialog path={path} bot={bot} onClose={() => {
      setTesting(false);
      onChanged();
    }} onChanged={onChanged}/>}
  </Dialog>;
}
