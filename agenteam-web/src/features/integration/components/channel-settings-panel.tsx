"use client";

import {useRef, useState} from "react";
import {Button} from "@/components/ui/button";
import {DetailStatus, SettingRow} from "@/components/ui/detail-section";
import {IconBell, IconKey, IconPlug, IconUserMinus} from "@/components/ui/icons";
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
  const update = (value: ChannelBinding, field: "receiveEnabled" | "externalLoginEnabled", enabled: boolean) => run(async () => {
    await action.mutation.run(`${path}/${encodeURIComponent(value.id!)}`, {method: "PATCH", revision: value.revision!,
      body: {receiveEnabled: value.receiveEnabled, externalLoginEnabled: value.externalLoginEnabled, [field]: enabled}});
    changed();
  });

  return <div className={styles.settings}>
    <p className={ui.description}>{t("绑定当前企业的账号，用于接收通知或登录。")}</p>
    <MutationFeedback action={action} onReload={changed}/>
    <QueryState {...list} hasData={Boolean(list.data?.length)} empty={t("企业尚未开放账号绑定，请联系管理员。")}>
      <div className={styles.list}>{list.data?.map((value) => <article className={styles.bindingCard} key={value.connectionId}>
        <div className={styles.bindingHeader}><ChannelIdentity name={value.connectionName} providerName={value.providerName} detail={value.displayName}/>
          <DetailStatus tone={value.status === "active" ? "success" : "neutral"}>{value.status === "active" ? t("已绑定") : value.status === "disabled" ? t("绑定已停用") : t("未绑定")}</DetailStatus></div>
        {value.id ? <>
          <div className={styles.bindingBody}>
            <SettingRow icon={<IconBell size={18}/>} label={t("接收通知")} description={t("通过此账号接收工作提醒。")}
              checked={value.receiveEnabled} disabled={action.busy || value.status !== "active"}
              onChange={(checked) => update(value, "receiveEnabled", checked)}/>
            {!superAdmin && <SettingRow icon={<IconKey size={18}/>} label={t("允许使用此账号登录")}
              description={t("使用已绑定的企业账号进入工作空间。")} checked={value.externalLoginEnabled}
              disabled={action.busy || value.status !== "active" || !value.loginAvailable && !value.externalLoginEnabled}
              onChange={(checked) => update(value, "externalLoginEnabled", checked)}/>}
          </div>
          {!value.messagingAvailable && <p className={styles.bindingNotice}>{t("企业暂未开放此渠道的通知发送。")}</p>}
          <div className={styles.bindingFooter}><Button className={ui.danger} disabled={action.busy} onClick={() => setRevoking(value)}><IconUserMinus size={16}/>{t("解除绑定")}</Button></div>
        </> : <div className={styles.bindingFooter}><Button className={ui.primary} disabled={action.busy || !value.bindingAvailable}
          onClick={() => run(async () => {
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
      <p>{t("解除后，此账号将不能用于登录或接收新的通知。")}</p>
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
  </div>;
}
