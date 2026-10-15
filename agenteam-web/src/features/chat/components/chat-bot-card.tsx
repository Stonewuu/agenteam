"use client";

import {Button} from "@/components/ui/button";
import {DetailStatus} from "@/components/ui/detail-section";
import {IconDots, IconPencil, IconPlayerPause, IconPlayerPlay, IconPlug, IconRefresh, IconRobot, IconShield} from "@/components/ui/icons";
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from "@/components/ui/shadcn/dropdown-menu";
import {useT} from "@/lib/i18n/locale-provider";
import type {ChatBot} from "../types/chat";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatBotCard({bot, canManage, busy, onEdit, onConnection, onChecks, onDiscover, onToggle}: {
  bot: ChatBot; canManage: boolean; busy: boolean; onEdit: () => void; onConnection: () => void;
  onChecks: () => void; onDiscover: () => void; onToggle: (enabled: boolean) => void;
}) {
  const t = useT();
  const enabled = bot.status === "enabled" && bot.chatEnabled;
  return <article className={styles.card}>
    <div className={styles.heading}><div className={styles.botIdentity}><span className={styles.botIcon} data-icon-surface><IconRobot size={22} variant="Bulk"/></span>
      <div><h3>{bot.roleName}</h3><p>{t(bot.providerCode === "feishu" ? "飞书" : "企微智能机器人")}{bot.roleDescription ? ` · ${bot.roleDescription}` : ""}</p></div></div>
      <DetailStatus tone={enabled ? "success" : bot.status === "disabled" ? "neutral" : "warning"}>{t(enabled ? "已启用" : bot.status === "disabled" ? "已停用" : "待启用")}</DetailStatus>
    </div>
    {bot.connectionError === "CHAT_CONNECTION_REPLACED" && <p className={styles.notice}>{t("平台已将消息交给另一个连接。检查其他接收程序后，重新启用此机器人。")}</p>}
    <div className={styles.botFooter}>
      {bot.agentName && <span className={styles.agentLabel}><IconRobot size={16}/>{bot.agentName}</span>}
      <div className={styles.actions}>
        {canManage && !enabled && <Button className={ui.primary} disabled={busy} onClick={() => onToggle(true)}><IconPlayerPlay size={16}/>{t("启用")}</Button>}
        {canManage && <Button className={ui.button} disabled={busy} onClick={onEdit}><IconPencil size={16}/>{t("设置")}</Button>}
        <DropdownMenu><DropdownMenuTrigger render={<Button className={canManage ? ui.button : "icon-button"} aria-label={t("{0}的更多操作", [bot.roleName])}><IconDots size={16}/>{canManage && t("更多")}</Button>}/>
          <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
            <DropdownMenuItem onClick={onConnection}><IconPlug size={16}/>{t("连接设置")}</DropdownMenuItem>
            <DropdownMenuItem onClick={onChecks}><IconShield size={16}/>{t("连接检查")}</DropdownMenuItem>
            {canManage && bot.providerCode === "feishu" && <DropdownMenuItem disabled={busy || bot.status !== "enabled"} onClick={onDiscover}><IconRefresh size={16}/>{t("刷新已加入的群")}</DropdownMenuItem>}
            {canManage && bot.status === "enabled" && <DropdownMenuItem disabled={busy} onClick={() => onToggle(false)}><IconPlayerPause size={16}/>{t("停用")}</DropdownMenuItem>}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  </article>;
}
