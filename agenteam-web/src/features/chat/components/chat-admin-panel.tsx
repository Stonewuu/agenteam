"use client";

import {useState} from "react";
import {Button} from "@/components/ui/button";
import {DetailStatus} from "@/components/ui/detail-section";
import {Guidance} from "@/components/ui/guidance";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {IconRobot, IconMessages, IconHistory, IconRefresh, IconPencil, IconPlayerPause} from "@/components/ui/icons";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {Tabs} from "@/components/ui/tabs";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiPage, useApiQuery} from "@/lib/http/use-api-query";
import {integrationPath, type Integration} from "@/features/integration/types/integration";
import {useT} from "@/lib/i18n/locale-provider";
import {chatPath, deliveryStatus, type ChatBot, type ChatGroup, type ChatOutbound} from "../types/chat";
import {ChatBotEditor} from "./chat-bot-editor";
import {ChatGroupEditor} from "./chat-group-editor";
import {ChatBotCard} from "./chat-bot-card";
import {ChatBotChecksDialog} from "./chat-bot-checks-dialog";
import {ChatBotConnectionDialog} from "./chat-bot-connection-dialog";
import {ChatBotSetupGuide} from "./chat-bot-setup-guide";
import {FeishuQuickCreateDialog} from "@/features/integration/components/feishu-quick-create-dialog";
import {botSettings, pausedGroupSettings} from "../api/chat-bot-settings";
import ui from "@/components/ui/surface.module.css";
import styles from "./chat.module.css";

export function ChatAdminPanel({enterpriseId, system = false, canManage = true, canTest = true}: {
  enterpriseId: string; system?: boolean; canManage?: boolean; canTest?: boolean;
}) {
  const t = useT();
  const path = chatPath(enterpriseId, system);
  const [refresh, setRefresh] = useState(0);
  const [tab, setTab] = useState<"bots" | "groups" | "outbounds">("bots");
  const [editor, setEditor] = useState<{bot: ChatBot | null; connection?: Integration} | null>(null);
  const [group, setGroup] = useState<ChatGroup | null>(null);
  const [probe, setProbe] = useState<ChatBot | null>(null);
  const [connectionBot, setConnectionBot] = useState<ChatBot | null>(null);
  const [pausing, setPausing] = useState<ChatGroup | null>(null);
  const [latestGroups, setLatestGroups] = useState<Record<string, ChatGroup>>({});
  const [retry, setRetry] = useState<ChatOutbound | null>(null);
  const [createdBot, setCreatedBot] = useState(false);
  const [quickCreate, setQuickCreate] = useState(false);
  const action = useFormAction();
  const service = useApiQuery<{paused: boolean}>(`${path}/status`, refresh);
  const bots = useApiPage<ChatBot>(`${path}/bots`, refresh, 0);
  const pending = useApiPage<Integration>(`${integrationPath(enterpriseId, system)}?purpose=chat_bot&unboundBotOnly=true`, refresh, 0, 30, tab === "bots");
  const groups = useApiPage<ChatGroup>(`${path}/conversations`, refresh, 0, 30, tab === "groups");
  const messages = useApiPage<ChatOutbound>(`${path}/outbounds`, refresh, 0, 30, tab === "outbounds");
  const changed = () => setRefresh((value) => value + 1);
  const pendingItems = pending.data?.items.filter((connection) => !bots.data?.items.some((bot) => bot.connectionId === connection.id)) ?? [];
  const groupItems = groups.data?.items.map((value) => latestGroups[value.id]?.revision >= value.revision ? latestGroups[value.id] : value) ?? [];
  const saved = () => {
    if (editor && !editor.bot) {
      setCreatedBot(true);
    }
    setEditor(null);
    setGroup(null);
    changed();
  };
  const discoverable = bots.data?.items.filter((bot) => bot.providerCode === "feishu" && bot.status === "enabled") ?? [];
  const refreshBotGroups = async (bot: ChatBot) => {
    let cursor: string | null = null;
    const visited = new Set<string>();
    do {
      const result: {discovered: number; nextCursor: string | null} = await action.mutation.run(`${path}/bots/${bot.id}/conversations/discover${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`, {method: "POST"});
      cursor = result.nextCursor;
      if (cursor && visited.has(cursor)) {
        throw new Error(t("群列表未能完整刷新，请稍后重试。"));
      }
      if (cursor) {
        visited.add(cursor);
      }
    } while (cursor);
  };
  const discover = (bot: ChatBot) => void action.execute(async () => {
    await refreshBotGroups(bot);
    changed();
    setTab("groups");
  }, t("群列表已刷新。"));
  return <div className={styles.panel}>
    {service.data?.paused && <p role="status" className={styles.notice}>
      <strong>{t("聊天服务已暂停")}</strong><br/>{t("机器人暂时无法接收和回复消息，请联系系统管理员恢复服务。")}
    </p>}
    {service.error && <p role="alert" className={styles.notice}>{t("暂时无法确认聊天服务是否可用，请刷新重试。")}</p>}
    <div className={styles.toolbar}><Tabs value={tab} onChange={setTab} label={t("机器人管理")} items={[
      {value: "bots", label: t("机器人列表"), icon: IconRobot}, {value: "groups", label: t("聊天会话"), icon: IconMessages}, {value: "outbounds", label: t("发送记录"), icon: IconHistory},
    ]}/><div className={styles.actions}><Button className={ui.button} onClick={changed}><IconRefresh size={16}/>{t("刷新")}</Button>
      {tab === "bots" && <ChatBotSetupGuide/>}
      {canManage && tab === "groups" && discoverable.length > 0 && <Button className={ui.button} disabled={action.busy} onClick={() => void action.execute(async () => {
        for (const bot of discoverable) {
          await refreshBotGroups(bot);
        }
        changed();
      }, t("群列表已刷新。"))}><IconMessages size={16}/>{t("刷新已加入的群")}</Button>}
      {canManage && canTest && tab === "bots" && <Button className={ui.primary} onClick={() => setQuickCreate(true)}><IconRobot size={16}/>{t("快捷创建飞书机器人")}</Button>}
      {canManage && tab === "bots" && <Button className={ui.button} onClick={() => setEditor({bot: null})}><IconRobot size={16}/>{t("手动添加机器人")}</Button>}</div></div>
    {quickCreate && <FeishuQuickCreateDialog enterpriseId={enterpriseId} system={system} onClose={() => setQuickCreate(false)} onSaved={() => {
      setQuickCreate(false);
      setCreatedBot(true);
      changed();
    }}/>}
    <MutationFeedback action={action} onReload={changed}/>
    <AnimatedHeight preserveControlShadows>{createdBot && tab === "bots" && <Guidance title={t("下一步：把机器人加入群聊")} icon={<IconMessages size={19}/>}
      actions={<Button className={ui.primary} onClick={() => {
        setCreatedBot(false);
        setTab("groups");
      }}>{t("管理群聊")}</Button>}>
      <p>{t("飞书机器人加入群聊后，直接 @ 机器人即可开始。需要调整参与岗位或成果展示时，再到聊天会话中设置。")}</p>
      <p>{t("企业微信：把机器人加入群聊后，在这里选择参与岗位并保存。")}</p>
    </Guidance>}</AnimatedHeight>
    <AnimatedHeight preserveControlShadows>
      {tab === "bots" && <><QueryState {...bots} hasData={Boolean(bots.data?.items.length || pending.data?.items.length)} empty={t("尚未绑定岗位机器人。")}>
        <div className={styles.list}>{bots.data?.items.map((bot) => <ChatBotCard key={bot.id} bot={bot} canManage={canManage} busy={action.busy}
          onEdit={() => setEditor({bot})} onConnection={() => setConnectionBot(bot)} onChecks={() => setProbe(bot)} onDiscover={() => discover(bot)}
          onToggle={(enabled) => void action.execute(async () => {
            await action.mutation.run(`${path}/bots/${bot.id}`, {method: "PATCH", body: botSettings(bot, enabled)});
            changed();
          }, t(enabled ? "机器人已启用。" : "机器人已停用。"))}/>)}</div>
        </QueryState><Pagination {...bots} hasMore={bots.data?.hasMore}/>
        {(Boolean(pendingItems.length) || pending.error) && <QueryState {...pending} hasData={Boolean(pendingItems.length)} empty="">
          <div className={styles.list}>{pendingItems.map((connection) => <article className={`${styles.card} ${styles.pending}`} key={connection.id}>
            <div className={styles.heading}><div><h3>{connection.name}</h3><p>{connection.providerName}</p></div><DetailStatus tone="warning">{t("未完成配置")}</DetailStatus></div>
            {connection.lastCheckError && <p className={ui.error}>{connection.lastCheckError}</p>}
            {canManage && <div className={styles.actions}><Button className={ui.primary} onClick={() => setEditor({bot: null, connection})}><IconPencil size={16}/>{t("继续配置")}</Button></div>}
          </article>)}</div><Pagination {...pending} hasMore={pending.data?.hasMore}/>
        </QueryState>}
      </>}
      {tab === "groups" && <div className={styles.groupSection}><Guidance title={t("把机器人加入需要协作的群聊")} icon={<IconMessages size={19}/>}>
        <p><strong>{t("飞书：")}</strong>{t("把机器人加入群聊后，直接 @ 机器人并描述任务，无需回到网页刷新或启用。")}</p>
        <p>{t("企业微信：把机器人加入群聊后，在这里选择参与岗位并保存。")}</p>
      </Guidance><QueryState {...groups} hasData={Boolean(groups.data?.items.length)} empty={t("尚未发现群聊，请先将机器人加入群聊。") }>
        <div className={styles.list}>{groupItems.map((value) => <article className={styles.card} key={value.id}>
          <div className={styles.heading}><div><h3>{value.name ?? t(value.kind === "direct" ? "机器人私聊" : "群聊")}</h3><p>{value.bots.filter((member) => member.collaborationEnabled).map((member) => member.roleName).join("、") || t(value.platformCode === "feishu" && value.status === "discovered" ? "在群里 @ 机器人即可开始。" : "尚未选择参与岗位")}</p></div>
            <DetailStatus tone={value.status === "enabled" ? "success" : value.status === "discovered" && value.platformCode !== "feishu" ? "warning" : "neutral"}>{t(value.status === "enabled" ? "已启用" : value.status === "discovered" ? value.platformCode === "feishu" ? "等待消息" : "待配置" : "已停用")}</DetailStatus></div>
          {value.status === "enabled" && <p className={styles.muted}>{t(value.kind === "direct" ? "直接发送消息即可开始对话。" : value.ingressMode === "multi_bot" ? "在群里 @ 已开启接收的机器人即可发起任务。" : "在群里 @ {0} 对应的机器人发起任务。", [value.bots.find((bot) => bot.botId === value.ingressBotId)?.roleName ?? t("指定岗位")])}</p>}
          <div className={styles.actions}>{canManage && <><Button className={ui.button} onClick={() => setGroup(value)}><IconPencil size={16}/>{t("设置参与岗位")}</Button>
            {value.status === "enabled" && <Button className={ui.button} onClick={() => setPausing(value)}><IconPlayerPause size={16}/>{t("停用")}</Button>}</>}</div>
        </article>)}</div></QueryState><Pagination {...groups} hasMore={groups.data?.hasMore}/></div>}
      {tab === "outbounds" && <><QueryState {...messages} hasData={Boolean(messages.data?.items.length)} empty={t("暂无聊天发送记录。")}>
        <div className={styles.list}>{messages.data?.items.map((message) => <article className={styles.card} key={message.id}><div className={styles.heading}>
          <div><h3>{bots.data?.items.find((bot) => bot.id === message.botId)?.roleName ?? t("岗位机器人")}</h3><p><EnterpriseDateTime value={message.createdAt}/></p></div>
          <DetailStatus>{t(deliveryStatus[message.status] ?? "暂时无法读取状态")}</DetailStatus></div>
          {message.status === "unknown" && <p className={styles.muted}>{t("平台可能已接受此消息，请先在会话中核实。")}</p>}
          {canManage && message.status === "failed" && <div className={styles.actions}><Button className={ui.button} onClick={() => setRetry(message)}>{t("重试发送")}</Button></div>}
        </article>)}</div></QueryState><Pagination {...messages} hasMore={messages.data?.hasMore}/></>}
    </AnimatedHeight>
    {editor && <ChatBotEditor enterpriseId={enterpriseId} system={system} initial={editor.bot} connection={editor.connection} canTest={canTest} onClose={() => {
      setEditor(null);
      changed();
    }} onSaved={saved}/>}
    {group && <ChatGroupEditor path={path} value={group} onClose={() => setGroup(null)} onSaved={(value) => {
      setLatestGroups((current) => ({...current, [value.id]: value}));
      saved();
    }}/>}
    {probe && <ChatBotChecksDialog path={path} bot={bots.data?.items.find((item) => item.id === probe.id) ?? probe} canTest={canTest} onClose={() => {
      setProbe(null);
      changed();
    }} onChanged={changed}/>}
    {connectionBot && <ChatBotConnectionDialog enterpriseId={enterpriseId} system={system} bot={connectionBot} canManage={canManage} canTest={canTest}
      onClose={() => setConnectionBot(null)} onChanged={changed}/>}
    {pausing && <Dialog title={t("停用此会话")} icon={<IconPlayerPause size={22}/>} onClose={() => setPausing(null)} busy={action.busy}>
      <p>{pausing.name ?? t("群聊")}</p><p>{t("停用后，此会话的机器人将停止接收任务和回传消息，已保存成果保留。")}</p><MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel><Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
        const value = await action.mutation.run<ChatGroup>(`${path}/conversations/${pausing.id}`, {method: "PATCH", body: pausedGroupSettings(pausing)});
        setLatestGroups((current) => ({...current, [value.id]: value}));
        setPausing(null);
        changed();
      }, t("会话已停用。"))}>{t("停用")}</Button></DialogActions>
    </Dialog>}
    {retry && <Dialog title={t("重试发送聊天消息")} icon={<IconHistory size={22}/>} onClose={() => setRetry(null)} busy={action.busy}>
      <p>{t("重试这条明确失败的发送记录。智能体工作不会重新执行。")}</p><p><EnterpriseDateTime value={retry.createdAt}/></p><MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel><Button className={ui.primary} disabled={action.busy} onClick={() => void action.execute(async () => {
        await action.mutation.run(`${path}/outbounds/${retry.id}/retry`, {method: "POST", body: {expectedRevision: retry.revision}});
        setRetry(null);
        changed();
      }, t("已提交发送重试。"))}>{t("重试发送")}</Button></DialogActions>
    </Dialog>}
  </div>;
}
