"use client";

import {useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {DetailStatus, SettingRow} from "@/components/ui/detail-section";
import {Guidance} from "@/components/ui/guidance";
import {IconBell, IconKey, IconPlug, IconUserMinus, IconMessages} from "@/components/ui/icons";
import {EnterpriseDateTime} from "@/features/auth/components/enterprise-date-time";
import {Dialog, DialogActions, DialogCancel} from "@/components/ui/dialog";
import {QueryState} from "@/components/ui/query-state";
import {LocalReauthentication} from "@/features/auth/components/local-reauthentication";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {ApiError, apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {channelsPath, type ChannelBinding} from "../types/channel-binding";
import {ChannelPreferencePanel} from "./channel-preference-panel";
import {ChannelIdentity} from "./channel-identity";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";

export function ChannelSettingsPanel({enterpriseId, superAdmin = false}: {enterpriseId: string; superAdmin?: boolean}) {
  const t = useT();
  const path = channelsPath(enterpriseId);
  const [refresh, setRefresh] = useState(0);
  const [reauthenticate, setReauthenticate] = useState(false);
  const [revoking, setRevoking] = useState<ChannelBinding | null>(null);
  const [pairing, setPairing] = useState<{command: string; expiresAt: string; name: string} | null>(null);
  const pending = useRef<(() => Promise<void>) | null>(null);
  const list = useApiQuery<ChannelBinding[]>(path, refresh);
  const action = useFormAction();
  const changed = () => setRefresh((value) => value + 1);
  const run = (operation: () => Promise<void>) => {
    void action.execute(async () => {
      try {
        await operation();
      } catch (error) {
        if (error instanceof ApiError && error.code === "LOCAL_REAUTH_REQUIRED") {
          pending.current = operation;
          setReauthenticate(true);
          return;
        }
        throw error;
      }
    }, "");
  };
  const update = (value: ChannelBinding, field: "receiveEnabled" | "externalLoginEnabled" | "chatEnabled", enabled: boolean) => run(async () => {
    await action.mutation.run(`${path}/${encodeURIComponent(value.id!)}`, {method: "PATCH", revision: value.revision!,
      body: {receiveEnabled: value.receiveEnabled, externalLoginEnabled: value.externalLoginEnabled, chatEnabled: value.chatEnabled, [field]: enabled}});
    changed();
  });

  return <div className={styles.settings}>
    <p className={ui.description}>{t("关联你本人的企业账号，用于与机器人对话、接收通知或登录群策。")}</p>
    <MutationFeedback action={action} onReload={changed}/>
    <QueryState {...list} hasData={Boolean(list.data?.length)} empty={t("企业尚未开放账号绑定，请联系管理员。")}>
      <div className={styles.list}>{list.data?.map((value) => <article className={styles.bindingCard} key={value.connectionId}>
        <div className={styles.bindingHeader}><ChannelIdentity name={value.connectionName} providerName={value.providerName} detail={value.displayName}/>
          <DetailStatus tone={value.status === "active" ? "success" : "neutral"}>{value.status === "active" ? t("已绑定") : value.status === "disabled" ? t("绑定已停用") : t("未绑定")}</DetailStatus></div>
        {value.id ? <>
          <div className={styles.bindingBody}>
            {value.status === "active" && value.chatEnabled && ["feishu", "wecom_aibot"].includes(value.providerCode) &&
              <div className={styles.bindingGuidance}><Guidance title={t("账号已关联，可以回到群聊使用")} tone="success" icon={<IconMessages size={19}/>}>
                <p>{t("在已配置的群聊中，通过 @ 菜单选择机器人并描述任务；需要补充信息时，回复提问并 @ 机器人即可。")}</p>
              </Guidance></div>}
            {value.providerCode !== "wecom_aibot" && (value.messagingAvailable || value.receiveEnabled) && <SettingRow icon={<IconBell size={18}/>} label={t("接收通知")} description={t("通过此账号接收工作提醒。")}
              checked={value.receiveEnabled} disabled={action.busy || value.status !== "active"}
              onChange={(checked) => update(value, "receiveEnabled", checked)}/>}
            {["feishu", "wecom_aibot"].includes(value.providerCode) && <SettingRow icon={<IconMessages size={18}/>} label={t("允许通过此账号使用智能体")}
              description={t("在机器人私聊或已启用的群聊中发起任务。")}
              checked={value.chatEnabled} disabled={action.busy || value.status !== "active"} onChange={(checked) => update(value, "chatEnabled", checked)}/>}
            {!superAdmin && value.providerCode !== "wecom_aibot" && (value.loginAvailable || value.externalLoginEnabled) && <SettingRow icon={<IconKey size={18}/>} label={t("允许使用此账号登录")}
              description={t("使用已绑定的企业账号进入工作空间。")} checked={value.externalLoginEnabled}
              disabled={action.busy || value.status !== "active" || !value.loginAvailable && !value.externalLoginEnabled}
              onChange={(checked) => update(value, "externalLoginEnabled", checked)}/>}
          </div>
          {value.providerCode !== "wecom_aibot" && value.receiveEnabled && !value.messagingAvailable && <p className={styles.bindingNotice}>{t("企业暂未开放此渠道的通知发送。")}</p>}
          <div className={styles.bindingFooter}><Button className={ui.danger} disabled={action.busy} onClick={() => setRevoking(value)}><IconUserMinus size={16}/>{t("解除绑定")}</Button></div>
        </> : <div className={styles.bindingFooter}><Button className={ui.primary} disabled={action.busy || !value.bindingAvailable}
          onClick={() => run(async () => {
            if (value.providerCode === "wecom_aibot") {
              const result = await apiRequest<{command: string; expiresAt: string}>(`/api/v1/enterprises/${encodeURIComponent(enterpriseId)}/chat/bindings/pairing`,
                {method: "POST", body: {connectionId: value.connectionId}});
              setPairing({...result, name: value.connectionName});
              return;
            }
            const result = await apiRequest<{authorizationUrl: string}>(`${path}/${encodeURIComponent(value.connectionId)}/authorize`,
              {method: "POST", body: {embeddedClient: /wxwork/i.test(navigator.userAgent)}});
            window.location.assign(result.authorizationUrl);
          })}><IconPlug size={17}/>{t("授权并绑定")}</Button></div>}
      </article>)}</div>
    </QueryState>
    <ChannelPreferencePanel enterpriseId={enterpriseId} refresh={refresh} busy={action.busy}
      onUpdate={(connectionId, category, revision, enabled) => run(async () => {
        await action.mutation.run(`/api/v1/enterprises/${encodeURIComponent(enterpriseId)}/me/channel-preferences`, {
          method: "PUT", revision, body: {connectionId, category, enabled}
        });
        changed();
      })}/>
    {revoking && !reauthenticate && <Dialog title={t("解除账号绑定")} icon={<IconUserMinus size={21}/>} onClose={() => setRevoking(null)} busy={action.busy}>
      <ChannelIdentity name={revoking.connectionName} providerName={revoking.providerName} detail={revoking.displayName}/>
      <p>{t("解除后，此平台账号将不能用于与机器人对话、登录或接收新的通知。")}</p>
      <MutationFeedback action={action}/>
      <DialogActions><DialogCancel className={ui.button} disabled={action.busy}>{t("取消")}</DialogCancel>
        <Button className={ui.danger} disabled={action.busy} onClick={() => run(async () => {
          await action.mutation.run(`${path}/${encodeURIComponent(revoking.id!)}`, {method: "DELETE", revision: revoking.revision!});
          setRevoking(null);
          changed();
        })}>{t("解除绑定")}</Button></DialogActions>
    </Dialog>}
    {reauthenticate && <LocalReauthentication onClose={() => {
      setReauthenticate(false);
      pending.current = null;
    }} onSuccess={() => {
      setReauthenticate(false);
      const operation = pending.current;
      pending.current = null;
      if (operation) {
        run(operation);
      }
    }}/>}
    {pairing && <Dialog title={t("私聊机器人完成绑定")} icon={<IconMessages size={21}/>} onClose={() => {
      setPairing(null);
      changed();
    }}>
      <p>{t("请私聊“{0}”发送下面的配对文字。", [pairing.name])}</p><p style={{overflowWrap: "anywhere", userSelect: "all"}}>{pairing.command}</p>
      <p>{t("有效期至")} <EnterpriseDateTime value={pairing.expiresAt}/></p>
      <DialogActions><DialogCancel className={ui.button}>{t("完成后刷新绑定")}</DialogCancel></DialogActions>
    </Dialog>}
  </div>;
}
