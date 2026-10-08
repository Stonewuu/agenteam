"use client";

import Link from "next/link";
import {Button} from "@/components/ui/button";
import {IconArrowLeft, IconArrowRight, IconKey} from "@/components/ui/icons";
import {QueryState} from "@/components/ui/query-state";
import {AuthFrame} from "@/features/auth/components/auth-frame";
import {useFormAction} from "@/features/auth/hooks/use-form-action";
import {MutationFeedback} from "@/features/workspace/components/mutation-feedback";
import {apiRequest} from "@/lib/http/api-client";
import {useApiQuery} from "@/lib/http/use-api-query";
import {useT} from "@/lib/i18n/locale-provider";
import ui from "@/components/ui/surface.module.css";
import styles from "./integration.module.css";
import {ChannelIdentity} from "./channel-identity";

export function ChannelLoginPage({loginKey}: {loginKey: string}) {
  const t = useT();
  const path = `/api/v1/auth/channel-login/${encodeURIComponent(loginKey)}`;
  const info = useApiQuery<{name: string; providerName: string; providerCode: string}>(path);
  const action = useFormAction();
  return <AuthFrame className={styles.authPage}><section className={styles.authPanel}>
    <header className={styles.authHeading}><span><IconKey size={26} variant="Bulk"/></span>
      <h1>{t("企业账号登录")}</h1><p>{t("使用已绑定的企业账号继续登录。")}</p></header>
    <QueryState {...info} hasData={Boolean(info.data)} empty={t("此企业登录入口暂不可用。")}>{info.data && <div className={ui.form}>
      <div className={styles.loginConnection}><ChannelIdentity name={info.data.name} providerName={info.data.providerName} providerCode={info.data.providerCode}/></div>
      <Button className={`${ui.primary} ${styles.loginButton}`} disabled={action.busy} onClick={() => void action.execute(async () => {
        const result = await apiRequest<{authorizationUrl: string}>(path, {method: "POST",
          body: {embeddedClient: /wxwork/i.test(navigator.userAgent)}});
        window.location.assign(result.authorizationUrl);
      }, "")}>{action.busy ? t("正在跳转…") : t("通过 {platform} 登录", {platform: info.data.providerName})}<IconArrowRight size={18}/></Button>
      <MutationFeedback action={action}/>
    </div>}</QueryState>
    <Link className={styles.backLink} href="/login"><IconArrowLeft size={15}/>{t("使用账号密码登录")}</Link>
  </section></AuthFrame>;
}
