"use client";

import {type ReactNode, useState} from "react";
import {AnimatedHeight} from "@/components/ui/animated-height";
import {Button} from "@/components/ui/button";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {IconPlug, IconRefresh, IconBell, IconHistory, IconPencil, IconKey, IconTrash, IconDots, IconShield, IconPlayerPause, IconPlayerPlay, IconCheck, IconAlertCircle} from "@/components/ui/icons";
import {DetailStatus} from "@/components/ui/detail-section";
import {DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem} from "@/components/ui/shadcn/dropdown-menu";
import {ChannelIdentity} from "./channel-identity";
import {IntegrationDetailsDialog} from "./integration-details-dialog";
import {Pagination, QueryState} from "@/components/ui/query-state";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {useApiPage} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {integrationPath, type Integration} from "../types/integration";
import {IntegrationEditor} from "./integration-editor";
import {FeishuQuickCreateDialog} from "./feishu-quick-create-dialog";
import {IntegrationSetupGuide} from "./integration-setup-guide";
import {IntegrationTestMessageDialog} from "./integration-test-message-dialog";
import {ChannelDeliveryPanel} from "@/features/notification/components/channel-delivery-panel";
import {ChatBotSetupGuide} from "@/features/chat/components/chat-bot-setup-guide";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";

type Editor = {mode: "create" | "edit" | "secret"; value: Integration | null};

export function IntegrationConnections({enterpriseId, system = false, canManage = true, canTest = true, canViewDeliveries = true, enterpriseSelector, purpose = "enterprise", providerCode}: {
  enterpriseId: string; system?: boolean; canManage?: boolean; canTest?: boolean; canViewDeliveries?: boolean; enterpriseSelector?: ReactNode; purpose?: "enterprise" | "chat_bot"; providerCode?: string;
}) {
  const t = useT();
  const path = integrationPath(enterpriseId, system);
  const [refresh, setRefresh] = useState(0);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [quickCreate, setQuickCreate] = useState(false);
  const [details, setDetails] = useState<Integration | null>(null);
  const [deleting, setDeleting] = useState<Integration | null>(null);
  const [testing, setTesting] = useState<Integration | null>(null);
  const [history, setHistory] = useState<{value: Integration; deliveryId?: string} | null>(null);
  const action = useFormAction();
  const list = useApiPage<Integration>(`${path}?purpose=${purpose}${providerCode ? `&providerCode=${encodeURIComponent(providerCode)}` : ""}`, refresh);
  const configured = providerCode && list.data?.items.length === 1 ? list.data.items[0] : null;
  const changed = () => {
    setRefresh((value) => value + 1);
  };
  const mutate = (value: Integration, operation: "check" | "status") => {
    void action.execute(async () => {
      await action.mutation.run(`${path}/${encodeURIComponent(value.id)}/${operation}`, {
        method: operation === "check" ? "POST" : "PATCH", revision: value.revision,
        ...(operation === "status" ? {body: {status: value.status === "enabled" ? "disabled" : "enabled"}} : {})
      });
      changed();
    }, "");
  };

  return <div className={styles.adminPage}>
    <div className={styles.connectionToolbar}>
      {purpose === "chat_bot" ? <ChatBotSetupGuide/> : <IntegrationSetupGuide providerCode={providerCode}/>}
      <div className={ui.actions}><Button className={ui.button} disabled={list.loading} onClick={changed}><IconRefresh size={16}/>{t("刷新")}</Button>
        {canManage && canTest && purpose === "enterprise" && providerCode === "feishu" && <Button className={ui.primary}
          onClick={() => setQuickCreate(true)}><IconPlug size={16}/>{t("快捷配置企业接入")}</Button>}
        {canManage && purpose === "enterprise" && <Button className={providerCode === "feishu" && canTest ? ui.button : ui.primary} disabled={list.loading || Boolean(list.error)}
          onClick={() => setEditor(configured ? {mode: "edit", value: configured} : {mode: "create", value: null})}>
          {configured ? <IconPencil size={16}/> : <IconPlug size={16}/>}{t(configured ? "编辑企业接入" : providerCode === "feishu" ? "手动配置" : "配置企业接入")}</Button>}
      </div>
    </div>
    {enterpriseSelector && <div className={styles.enterpriseToolbar}>{enterpriseSelector}</div>}
    <MutationFeedback action={action} onReload={changed}/>
    <QueryState {...list} hasData={Boolean(list.data?.items.length)} empty={t(purpose === "chat_bot" ? "尚未添加机器人连接。" : "尚未配置此平台的企业接入。") }>
      <div className={styles.list}>
        {list.data?.items.map((value) => <article key={value.id} className={styles.card}>
          <div className={styles.heading}><ChannelIdentity name={value.name} providerName={value.providerName} providerCode={value.providerCode}/>
            <DetailStatus tone={value.status === "enabled" ? "success" : "neutral"}>{value.status === "enabled" ? t("已启用") : value.status === "disabled" ? t("已停用") : t("待启用")}</DetailStatus>
          </div>
          {purpose === "enterprise" && <div className={styles.capabilities}>{value.bindingEnabled && <span><IconPlug size={14}/>{t("账号绑定")}</span>}
            {value.loginEnabled && <span><IconKey size={14}/>{t("企业登录")}</span>}{value.messagingEnabled && <span><IconBell size={14}/>{t("消息通知")}</span>}</div>}
          <div className={styles.check}>
            {value.lastCheckStatus === "passed" ? <IconCheck size={16}/> : value.lastCheckStatus === "failed" ? <IconAlertCircle size={16}/> : <IconShield size={16}/>}
            <span>{value.lastCheckStatus === "passed" ? t("最近一次应用校验通过") : value.lastCheckStatus === "failed" ? t("最近一次应用校验未通过") : t("尚未校验应用")}</span>
            {value.lastCheckedAt && <EnterpriseDateTime value={value.lastCheckedAt}/>}
          </div>
          <AnimatedHeight>{value.lastCheckError && <p className={styles.error}>{value.lastCheckError}</p>}</AnimatedHeight>
          <div className={styles.actions}>
            <Button className={ui.button} onClick={() => setDetails(value)}><IconPlug size={16}/>{t("查看配置")}</Button>
            {canManage && <Button className={ui.button} disabled={action.busy} onClick={() => setEditor({mode: "edit", value})}><IconPencil size={16}/>{t("编辑")}</Button>}
            {canViewDeliveries && purpose === "enterprise" && <Button className={ui.button} onClick={() => setHistory({value})}><IconHistory size={16}/>{t("发送记录")}</Button>}
            {(canManage || canTest && purpose === "enterprise") && <DropdownMenu><DropdownMenuTrigger render={<Button className={ui.button} aria-label={t("{0}的更多操作", [value.name])}><IconDots size={16}/>{t("更多")}</Button>}/>
              <DropdownMenuContent align="end" className="agenteam-menu agenteam-popup">
                {canTest && purpose === "enterprise" && <DropdownMenuItem disabled={action.busy || value.status !== "enabled" || !value.messagingEnabled}
                  onClick={() => setTesting(value)}><IconBell size={16}/>{t("测试通知")}</DropdownMenuItem>}
                {canManage && canTest && <DropdownMenuItem disabled={action.busy} onClick={() => mutate(value, "check")}><IconShield size={16}/>{t("校验应用")}</DropdownMenuItem>}
                {canManage && <>
                <DropdownMenuItem disabled={action.busy || value.status !== "enabled" && value.lastCheckStatus !== "passed"}
                  onClick={() => mutate(value, "status")}>{value.status === "enabled" ? <IconPlayerPause size={16}/> : <IconPlayerPlay size={16}/>}{value.status === "enabled" ? t("停用") : t("启用")}</DropdownMenuItem>
                <DropdownMenuItem disabled={action.busy} onClick={() => setEditor({mode: "secret", value})}><IconKey size={16}/>{t("更换密钥")}</DropdownMenuItem>
                <DropdownMenuItem disabled={action.busy || value.status === "enabled"} onClick={() => setDeleting(value)}><IconTrash size={16}/>{t("删除")}</DropdownMenuItem>
                </>}
              </DropdownMenuContent></DropdownMenu>}
          </div>
        </article>)}
      </div>
    </QueryState>
    <Pagination {...list} hasMore={list.data?.hasMore}/>
    {quickCreate && <FeishuQuickCreateDialog enterpriseId={enterpriseId} system={system} mode="enterprise"
      onClose={() => {
        setQuickCreate(false);
        changed();
      }} onSaved={() => {
        setQuickCreate(false);
        changed();
      }}/>}
    {testing && <IntegrationTestMessageDialog path={path} value={testing} onClose={() => setTesting(null)} onSent={(deliveryId) => {
      setHistory({value: testing, deliveryId});
      setTesting(null);
    }}/>}
    {history && <Dialog title={t("通知发送记录")} icon={<IconHistory size={21}/>} onClose={() => setHistory(null)} wide>
      <ChannelDeliveryPanel enterpriseId={enterpriseId} system={system} connectionId={history.value.id} initialDeliveryId={history.deliveryId}/>
      <DialogActions><DialogCancel className={ui.button}>{t("关闭")}</DialogCancel></DialogActions>
    </Dialog>}
    {editor && <IntegrationEditor path={path} initial={editor.value} mode={editor.mode} purpose={purpose} defaultProvider={providerCode}
                                  onClose={() => setEditor(null)} onSaved={() => {
                                    setEditor(null);
                                    changed();
                                  }}/>}
    {details && <IntegrationDetailsDialog value={details} onClose={() => setDetails(null)}/>}
    {deleting && <Dialog title={t(purpose === "chat_bot" ? "删除机器人连接" : "删除企业接入")} icon={<IconTrash size={21}/>} busy={action.busy} onClose={() => setDeleting(null)}>
      <p>{t(purpose === "chat_bot" ? "删除后，使用此连接的机器人将无法处理新消息。" : "删除后，成员不能再通过此接入登录或接收新通知。")}</p>
      <MutationFeedback action={action} onReload={() => {
        setDeleting(null);
        changed();
      }}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button className={ui.danger} disabled={action.busy} onClick={() => void action.execute(async () => {
          await action.mutation.run(`${path}/${encodeURIComponent(deleting.id)}`, {method: "DELETE", revision: deleting.revision});
          setDeleting(null);
          changed();
        }, t("接入已删除。"))}>{t("删除")}</Button>
      </DialogActions>
    </Dialog>}
  </div>;
}
