"use client";

import Link from "next/link";
import {useState} from "react";
import {Button} from "@/components/ui/button";
import {SettingRow} from "@/components/ui/detail-section";
import {IconArrowLeft, IconArrowRight, IconBell, IconKey, IconPlug, IconUserCircle, IconCheck} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {AuthFrame} from "@/features/auth/components/auth-frame";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import {channelsPath, type ChannelAuthorizationReview} from "../types/channel-binding";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";

export function ChannelAuthorizationPage() {
  const t = useT();
  const review = useApiQuery<ChannelAuthorizationReview>("/api/v1/auth/channel-authorization");
  const [receiveEnabled, setReceiveEnabled] = useState(true);
  const [externalLoginEnabled, setExternalLoginEnabled] = useState(false);
  const action = useFormAction();
  return <AuthFrame className={styles.authPage}><section className={styles.authPanel}>
    <header className={styles.authHeading}><span><IconPlug size={26} variant="Bulk"/></span>
      <h1>{t("确认绑定账号")}</h1><p>{t("请确认这两个账号都属于你。")}</p></header>
    <QueryState {...review} hasData={Boolean(review.data)} empty={t("没有待确认的授权，请重新发起绑定。")}>{review.data && <div className={ui.form}>
      <dl className={styles.accountPair}>
        <div className={styles.accountCard}><dt><IconUserCircle size={16}/>{t("当前账号")}</dt>
          <dd>{review.data.localDisplayName}<small>{review.data.username}</small></dd></div>
        <IconArrowRight size={20}/>
        <div className={styles.accountCard}><dt><IconPlug size={16}/>{review.data.providerName}</dt>
          <dd>{review.data.externalDisplayName ?? review.data.externalSubjectId}<small>{review.data.connectionName}</small></dd></div>
      </dl>
      <div className={styles.authOptions}>
        <SettingRow icon={<IconBell size={18}/>} label={t("接收通知")} description={t("通过此账号接收工作提醒。")}
          checked={receiveEnabled} disabled={action.busy} onChange={setReceiveEnabled}/>
        {review.data.loginAvailable && <SettingRow icon={<IconKey size={18}/>} label={t("允许使用此账号登录")}
          description={t("下次可以通过企业账号进入工作空间。")}
          checked={externalLoginEnabled} disabled={action.busy} onChange={setExternalLoginEnabled}/>}
      </div>
      <MutationFeedback action={action}/>
      <div className={styles.authActions}>
        <Button className={ui.button} disabled={action.busy} onClick={() => void action.execute(async () => {
          await apiRequest("/api/v1/auth/channel-authorization/cancel", {method: "POST", body: {authorizationId: review.data!.authorizationId}});
          window.location.replace(`/enterprises/${encodeURIComponent(review.data!.enterpriseId)}/settings/channels`);
        }, "")}>{t("取消")}</Button>
        <Button className={ui.primary} disabled={action.busy} onClick={() => void action.execute(async () => {
          await action.mutation.run(`${channelsPath(review.data!.enterpriseId)}/confirm`, {method: "POST",
            body: {authorizationId: review.data!.authorizationId, receiveEnabled, externalLoginEnabled}});
          window.location.replace(`/enterprises/${encodeURIComponent(review.data!.enterpriseId)}/settings/channels`);
        }, "")}><IconCheck size={18}/>{action.busy ? t("正在确认…") : t("确认绑定")}</Button>
      </div>
    </div>}</QueryState>
    <Link className={styles.backLink} href="/settings?tab=channels"><IconArrowLeft size={15}/>{t("返回账号绑定设置")}</Link>
  </section></AuthFrame>;
}
